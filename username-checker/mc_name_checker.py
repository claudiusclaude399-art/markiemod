#!/usr/bin/env python3
"""
Minecraft username checker - command-line version.
(For the window version run mc_name_checker_gui.py or the .exe.)

Prints available names in GREEN and taken names in RED, saves available ones
to available.txt. Standard library only.

Examples:
    python mc_name_checker.py                         # 3-4 chars, letters + numbers
    python mc_name_checker.py --min 5 --max 6         # 5 and 6 character names
    python mc_name_checker.py --underscores           # also use _ (anywhere)
    python mc_name_checker.py --underscores one       # exactly one _ in the middle (a_b)
    python mc_name_checker.py --no-digits             # letters only
    python mc_name_checker.py --random --threads 24
    python mc_name_checker.py --start b2cd            # resume

Press Ctrl+C to stop.
"""

import argparse
import os
import sys
import threading
import time

import checker_core as core

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
GREY = "\033[90m"
RESET = "\033[0m"


def main():
    p = argparse.ArgumentParser(description="Check which Minecraft usernames are free.")
    p.add_argument("--min", type=int, default=3, help="shortest name length (3-16, default 3)")
    p.add_argument("--max", type=int, default=4, help="longest name length (3-16, default 4)")
    p.add_argument("--no-letters", action="store_true", help="don't use a-z")
    p.add_argument("--no-digits", action="store_true", help="don't use 0-9")
    p.add_argument("--underscores", nargs="?", const="anywhere", default="none",
                   choices=list(core.UNDERSCORE_MODES),
                   help="use _ : anywhere (default if given), max1 = at most one and not at "
                        "start/end, one = exactly one not at start/end")
    p.add_argument("--random", action="store_true", help="check names in random order")
    p.add_argument("--start", help="resume from this name (in-order mode)")
    p.add_argument("--threads", type=int, default=12, help="parallel requests (default 12)")
    p.add_argument("--only-available", action="store_true", help="don't print taken names")
    p.add_argument("--limit", type=int, help="stop after checking about this many names")
    p.add_argument("--out", default="available.txt", help="file for available names")
    args = p.parse_args()

    if not (core.MIN_LEN <= args.min <= args.max <= core.MAX_LEN):
        p.error("lengths must satisfy 3 <= --min <= --max <= 16")
    mode = args.underscores
    charset = core.build_charset(not args.no_letters, not args.no_digits, mode)
    if not charset:
        p.error("no characters left to use")
    start = args.start.lower() if args.start and not args.random else None
    if start and (any(c not in charset for c in start) or not args.min <= len(start) <= args.max):
        p.error("--start must fit the chosen lengths and characters")

    if os.name == "nt":
        os.system("")   # turn on ANSI colours in the Windows console

    total = core.count_names(args.min, args.max, charset, mode)
    print(f"Checking up to {total:,} names ({args.min}-{args.max} chars from '{charset}'). "
          f"Ctrl+C to stop.\nAvailable names are saved to {args.out}\n")

    out = open(args.out, "a", encoding="utf-8")
    print_lock = threading.Lock()

    def on_result(name, free):
        with print_lock:
            if free:
                print(f"{GREEN}{name:<17}AVAILABLE{RESET}")
                out.write(name + "\n")
                out.flush()
            elif not args.only_available:
                print(f"{RED}{name:<17}taken{RESET}")

    def on_status(text):
        with print_lock:
            print(f"{YELLOW}{text}{RESET}")

    names = core.generate_names(args.min, args.max, charset, start=start, shuffle=args.random,
                                mode=mode)
    checker = core.Checker(names, threads=args.threads, on_result=on_result,
                           on_status=on_status, charset=charset)
    checker.start()
    try:
        while checker.is_running():
            time.sleep(0.2)
            if args.limit and checker.checked >= args.limit:
                checker.stop()
    except KeyboardInterrupt:
        checker.stop()
    checker.join(10)
    out.close()

    with print_lock:
        print(f"\n{GREY}Checked {checker.checked:,} names, {checker.available:,} available "
              f"(saved to {args.out}).{RESET}")
        if checker.finished_all():
            print(f"{GREY}Every name checked.{RESET}")
        elif not args.random and checker.resume_point():
            print(f"{GREY}Resume later with: --start {checker.resume_point()}{RESET}")


if __name__ == "__main__":
    main()
