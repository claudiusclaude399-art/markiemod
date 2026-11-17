#!/usr/bin/env python3
"""
Minecraft short-username checker.

Goes through 3 and 4 character usernames, asks Mojang whether each one is
taken, and prints it in GREEN (available) or RED (taken), then moves on.
Available names are also saved to available.txt.

Only uses the Python standard library - no pip install needed.

Examples:
    python mc_name_checker.py                 # 3 and 4 chars, a-z 0-9 _
    python mc_name_checker.py --letters       # letters only (a-z)
    python mc_name_checker.py --length 4      # only 4-char names
    python mc_name_checker.py --random        # random order instead of aaa, aab, ...
    python mc_name_checker.py --start b2c     # resume from a name
    python mc_name_checker.py --only-available

Press Ctrl+C to stop.
"""

import argparse
import itertools
import json
import os
import random
import string
import sys
import time
import urllib.error
import urllib.request

BULK_URLS = [
    "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname",
    "https://api.mojang.com/profiles/minecraft",
]
BATCH_SIZE = 10  # Mojang allows up to 10 names per bulk lookup

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
GREY = "\033[90m"
RESET = "\033[0m"


def enable_colors():
    """Make ANSI colours work in the Windows terminal too."""
    if os.name == "nt":
        os.system("")


def generate_names(lengths, charset, start=None, shuffle=False):
    """Yield every name of the given lengths built from charset."""
    for length in lengths:
        if shuffle:
            # Walk the whole space in random order without storing it all.
            total = len(charset) ** length
            for idx in random_permutation(total):
                yield index_to_name(idx, length, charset)
        else:
            if start and length < len(start):
                continue
            skipping = bool(start) and length == len(start)
            for combo in itertools.product(charset, repeat=length):
                name = "".join(combo)
                if skipping:
                    if name != start:
                        continue
                    skipping = False
                yield name


def index_to_name(idx, length, charset):
    base = len(charset)
    chars = []
    for _ in range(length):
        idx, r = divmod(idx, base)
        chars.append(charset[r])
    return "".join(reversed(chars))


def random_permutation(n):
    """Visit 0..n-1 once each in a shuffled order (linear congruential walk)."""
    # Pick a modulus m >= n that is a power of two, then use a full-period LCG.
    m = 1
    while m < n:
        m <<= 1
    a = random.randrange(1, m // 4 + 1) * 4 + 1 if m >= 4 else 1  # a % 4 == 1
    c = random.randrange(0, m) | 1                                 # odd
    x = random.randrange(0, m)
    for _ in range(m):
        x = (a * x + c) % m
        if x < n:
            yield x


def batched(iterable, size):
    it = iter(iterable)
    while True:
        chunk = list(itertools.islice(it, size))
        if not chunk:
            return
        yield chunk


def lookup_taken(names, timeout=10):
    """
    Return the set of names (lowercase) that are taken.
    Raises RateLimited on HTTP 429, RuntimeError if every endpoint fails.
    """
    body = json.dumps(names).encode()
    last_error = None
    for url in BULK_URLS:
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json", "User-Agent": "mc-name-checker"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
                data = json.loads(raw) if raw else []
                return {p["name"].lower() for p in data if "name" in p}
        except urllib.error.HTTPError as e:
            if e.code == 429:
                raise RateLimited()
            last_error = f"HTTP {e.code} from {url}"
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            last_error = f"{e} ({url})"
    raise RuntimeError(last_error)


class RateLimited(Exception):
    pass


def main():
    parser = argparse.ArgumentParser(description="Check short Minecraft usernames.")
    parser.add_argument("--length", type=int, choices=[3, 4], nargs="+", default=[3, 4],
                        help="name lengths to check (default: 3 4)")
    parser.add_argument("--letters", action="store_true",
                        help="only use letters a-z (default also uses 0-9 and _)")
    parser.add_argument("--random", action="store_true", help="check names in random order")
    parser.add_argument("--start", help="skip ahead to this name (sequential mode)")
    parser.add_argument("--delay", type=float, default=1.0,
                        help="seconds to wait between requests of 10 names (default 1.0)")
    parser.add_argument("--only-available", action="store_true",
                        help="don't print taken names")
    parser.add_argument("--out", default="available.txt", help="file to save available names to")
    args = parser.parse_args()

    enable_colors()

    charset = string.ascii_lowercase
    if not args.letters:
        charset += string.digits + "_"

    lengths = sorted(set(args.length))
    start = args.start.lower() if args.start else None
    names = generate_names(lengths, charset, start=start, shuffle=args.random)

    total = sum(len(charset) ** n for n in lengths)
    print(f"Checking up to {total:,} names ({', '.join(map(str, lengths))} chars, "
          f"charset '{charset}'). Ctrl+C to stop.")
    print(f"Available names are saved to {args.out}\n")

    checked = available = 0
    backoff = 30
    out = open(args.out, "a", encoding="utf-8")
    try:
        for batch in batched(names, BATCH_SIZE):
            while True:
                try:
                    taken = lookup_taken(batch)
                    backoff = 30
                    break
                except RateLimited:
                    print(f"{YELLOW}Rate limited by Mojang - waiting {backoff}s...{RESET}")
                    time.sleep(backoff)
                    backoff = min(backoff * 2, 300)
                except RuntimeError as e:
                    print(f"{YELLOW}Lookup failed ({e}) - retrying in 10s...{RESET}")
                    time.sleep(10)

            for name in batch:
                checked += 1
                if name.lower() in taken:
                    if not args.only_available:
                        print(f"{RED}{name:<5} taken{RESET}")
                else:
                    available += 1
                    print(f"{GREEN}{name:<5} AVAILABLE{RESET}")
                    out.write(name + "\n")
                    out.flush()

            time.sleep(args.delay)
    except KeyboardInterrupt:
        pass
    finally:
        out.close()
        print(f"\n{GREY}Checked {checked:,} names, {available:,} available "
              f"(saved to {args.out}).{RESET}")
        if checked and not args.random:
            print(f"{GREY}Resume later with: --start {batch[-1]}{RESET}")


if __name__ == "__main__":
    main()
