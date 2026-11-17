"""
Engine for the Minecraft username checker (used by both the GUI and the CLI).

Why it is fast:
  * several worker threads run at once
  * kept-alive HTTPS connections (no new TLS handshake per request)
  * bulk lookups: 10 names per request
  * several Mojang servers are used side by side, each with its own
    self-tuning speed limit, so when one says "slow down" (HTTP 429) the
    others keep going
"""

import collections
import heapq
import http.client
import itertools
import json
import random
import re
import ssl
import string
import threading
import time
from urllib.parse import quote, urlsplit

LETTERS = string.ascii_lowercase
DIGITS = string.digits
UNDERSCORE = "_"
MIN_LEN, MAX_LEN = 3, 16

USER_AGENT = "mc-name-checker/2.0"


# --------------------------------------------------------------------------- names

# How underscores may appear (only matters when "_" is in the character set)
UNDERSCORE_MODES = {
    "none": "None",
    "anywhere": "Anywhere",
    "max1": "At most 1, not at start/end",
    "one": "Exactly 1, not at start/end",
}


def build_charset(letters=True, digits=True, underscore=False):
    """underscore: True/False, or an UNDERSCORE_MODES key ("none" = no underscores)."""
    cs = ""
    if letters:
        cs += LETTERS
    if digits:
        cs += DIGITS
    if underscore and underscore != "none":
        cs += UNDERSCORE
    return cs


def name_allowed(name, mode="anywhere"):
    """Does `name` follow the underscore rule?"""
    if mode == "anywhere" or "_" not in name and mode != "one":
        return True
    if mode == "none":
        return False
    if name[0] == "_" or name[-1] == "_":
        return False
    u = name.count("_")
    return u == 1 if mode == "one" else u <= 1


def _per_length(n, charset, mode):
    k = len(charset.replace("_", ""))
    if "_" not in charset or mode == "none":
        return k ** n
    if mode == "anywhere":
        return (k + 1) ** n
    with_one = (n - 2) * k ** (n - 1) if n >= 3 else 0
    return with_one + (k ** n if mode == "max1" else 0)


def count_names(min_len, max_len, charset, mode="anywhere"):
    return sum(_per_length(n, charset, mode) for n in range(min_len, max_len + 1))


def _completions(prefix, n, charset, mode):
    """How many allowed names of length n start with `prefix`."""
    k = len(charset.replace("_", ""))
    r = n - len(prefix)
    if "_" not in charset or mode == "anywhere":
        return len(charset) ** r
    if mode == "none":
        return 0 if "_" in prefix else k ** r
    if r == 0:
        return 1 if name_allowed(prefix, mode) else 0
    if prefix and prefix[0] == "_":
        return 0
    u = prefix.count("_")
    if u > 1:
        return 0
    if u == 1:
        return k ** r                           # no more underscores allowed
    free = r - 1 if prefix else r - 2           # underscore can't be first or last
    with_one = max(free, 0) * k ** (r - 1)
    return with_one + (k ** r if mode == "max1" else 0)


def count_before(start, charset, mode, min_len):
    """How many allowed names come before `start` in checking order (for the progress bar)."""
    total = count_names(min_len, len(start) - 1, charset, mode) if len(start) > min_len else 0
    pos = {ch: i for i, ch in enumerate(charset)}
    for i, ch in enumerate(start):
        for c in charset[:pos[ch]]:
            total += _completions(start[:i] + c, len(start), charset, mode)
    return total


def _odometer(length, charset, begin=None):
    """All strings of `length` over charset in order, starting at `begin` (no skipping loop)."""
    base = len(charset)
    digits = [charset.index(c) for c in begin] if begin else [0] * length
    chars = [charset[d] for d in digits]
    while True:
        yield "".join(chars)
        i = length - 1
        while i >= 0:
            digits[i] += 1
            if digits[i] < base:
                chars[i] = charset[digits[i]]
                break
            digits[i] = 0
            chars[i] = charset[0]
            i -= 1
        if i < 0:
            return


def generate_names(min_len, max_len, charset, start=None, shuffle=False, mode="anywhere"):
    """Yield every allowed name with a length in [min_len, max_len] built from charset."""
    check = "_" in charset and mode != "anywhere"
    for length in range(min_len, max_len + 1):
        if shuffle:
            for idx in _random_permutation(len(charset) ** length):
                name = _index_to_name(idx, length, charset)
                if not check or name_allowed(name, mode):
                    yield name
            continue
        if start and length < len(start):
            continue
        begin = start if start and length == len(start) else None
        for name in _odometer(length, charset, begin):
            if not check or name_allowed(name, mode):
                yield name


class SkipRecent:
    """
    Wraps a name generator and drops names the `already_checked(names) -> set`
    callback says were checked recently (looked up 500 at a time).
    """

    def __init__(self, names, already_checked, chunk=500):
        self._names = iter(names)
        self._already = already_checked
        self._chunk = chunk
        self._buf = collections.deque()
        self.skipped = 0

    def __iter__(self):
        return self

    def __next__(self):
        while not self._buf:
            batch = list(itertools.islice(self._names, self._chunk))
            if not batch:
                raise StopIteration
            done = self._already(batch)
            keep = [n for n in batch if n not in done]
            self.skipped += len(batch) - len(keep)
            self._buf.extend(keep)
        return self._buf.popleft()


def _index_to_name(idx, length, charset):
    base = len(charset)
    out = []
    for _ in range(length):
        idx, r = divmod(idx, base)
        out.append(charset[r])
    return "".join(reversed(out))


def _random_permutation(n):
    """Visit 0..n-1 once each, in shuffled order, without storing them."""
    m = 1
    while m < n:
        m <<= 1
    if m < 4:
        order = list(range(n))
        random.shuffle(order)
        yield from order
        return
    a = random.randrange(1, m // 4) * 4 + 1      # a % 4 == 1  -> full period
    c = random.randrange(0, m) | 1               # odd
    x = random.randrange(0, m)
    for _ in range(m):
        x = (a * x + c) % m
        if x < n:
            yield x


def name_order_key(name, charset):
    pos = {ch: i for i, ch in enumerate(charset)}
    return (len(name), [pos.get(ch, 99) for ch in name])


# --------------------------------------------------------------------------- servers

class Endpoint:
    """One Mojang lookup URL with its own adaptive rate limit."""

    def __init__(self, label, url, kind, batch, start_rate=4.0, max_rate=60.0):
        self.label = label
        self.url = url
        self.kind = kind            # "bulk" (POST list) or "single" (GET one name)
        self.batch = batch
        self.rate = start_rate      # requests per second we allow ourselves
        self.max_rate = max_rate
        self.next_slot = 0.0
        self.cooldown_until = 0.0
        self.strikes = 0            # consecutive 429s
        self.smooth = True          # learn Mojang's limit and stay under it
        self.ceiling = max_rate     # highest rate that hasn't been rate-limited (smooth mode)
        self.streak = 0             # successes since the last 429
        self.errors = 0             # consecutive other failures
        self.lock = threading.Lock()

    def ready_at(self):
        return max(self.next_slot, self.cooldown_until)

    def reserve(self):
        """Book the next request slot, return the time it may be sent."""
        with self.lock:
            t = max(time.monotonic(), self.next_slot, self.cooldown_until)
            self.next_slot = t + 1.0 / self.rate
            return t

    def success(self):
        with self.lock:
            self.strikes = 0
            self.errors = 0
            self.streak += 1
            if self.smooth:
                # creep back up very slowly in case the limit was lifted
                if self.streak % 20 == 0:
                    self.ceiling = min(self.max_rate, self.ceiling * 1.05)
                self.rate = min(self.ceiling, self.rate + 1.0 / self.rate)
            else:
                self.rate = min(self.max_rate, self.rate + 1.0 / self.rate)   # ~ +1 req/s per second

    def rate_limited(self):
        with self.lock:
            now = time.monotonic()
            if now < self.cooldown_until:
                # a request that was already on its way when we paused - same strike
                return round(self.cooldown_until - now, 1)
            self.strikes += 1
            if self.smooth:
                # remember where the limit is and settle just below it
                self.ceiling = max(0.5, self.rate * 0.9)
                self.rate = self.ceiling
                wait = min(0.5 * 2 ** (self.strikes - 1), 10)   # 0.5, 1, 2 ... 10 s - short pauses
            else:
                self.rate = max(0.5, self.rate * 0.7)           # back off
                wait = min(0.5 * 2 ** (self.strikes - 1), 120)  # 0.5, 1, 2 ... 120 s
            self.streak = 0
            self.cooldown_until = now + wait
            self.next_slot = self.cooldown_until
            return wait

    def failed(self):
        with self.lock:
            self.errors += 1
            wait = 2 if self.errors < 5 else 60
            self.cooldown_until = time.monotonic() + wait
            return wait


def default_endpoints():
    return [
        Endpoint("minecraftservices (bulk)",
                 "https://api.minecraftservices.com/minecraft/profile/lookup/bulk/byname",
                 "bulk", 10),
        Endpoint("mojang (bulk)",
                 "https://api.mojang.com/profiles/minecraft",
                 "bulk", 10),
        Endpoint("mojang (single)",
                 "https://api.mojang.com/users/profiles/minecraft/{}",
                 "single", 1),
        Endpoint("minecraftservices (single)",
                 "https://api.minecraftservices.com/minecraft/profile/lookup/name/{}",
                 "single", 1),
    ]


class RateLimited(Exception):
    pass


class LookupFailed(Exception):
    pass


class AuthFailed(Exception):
    """The Minecraft login token is wrong or has expired."""


_ssl_ctx = ssl.create_default_context()
_local = threading.local()


def _connection(scheme, host):
    conns = getattr(_local, "conns", None)
    if conns is None:
        conns = _local.conns = {}
    key = (scheme, host)
    conn = conns.get(key)
    if conn is None:
        if scheme == "https":
            conn = http.client.HTTPSConnection(host, timeout=8, context=_ssl_ctx)
        else:
            conn = http.client.HTTPConnection(host, timeout=8)
        conns[key] = conn
    return conn


def _drop_connection(scheme, host):
    conns = getattr(_local, "conns", {})
    conn = conns.pop((scheme, host), None)
    if conn:
        try:
            conn.close()
        except Exception:
            pass


def _request(method, url, body=None, extra_headers=None):
    parts = urlsplit(url)
    path = parts.path + (("?" + parts.query) if parts.query else "")
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    headers.update(extra_headers or {})
    if body is not None:
        headers["Content-Type"] = "application/json"
    for attempt in (1, 2):      # retry once on a stale kept-alive connection
        conn = _connection(parts.scheme, parts.netloc)
        try:
            conn.request(method, path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
            if resp.getheader("Connection", "").lower() == "close":
                _drop_connection(parts.scheme, parts.netloc)
            return resp.status, data
        except (http.client.HTTPException, OSError) as e:
            _drop_connection(parts.scheme, parts.netloc)
            if attempt == 2:
                raise LookupFailed(f"{type(e).__name__}: {e}")


def lookup(endpoint, names):
    """Return the set of lowercase names that are TAKEN. Raises RateLimited / LookupFailed."""
    if endpoint.kind == "bulk":
        status, data = _request("POST", endpoint.url, json.dumps(names).encode())
        if status == 429:
            raise RateLimited()
        if status != 200:
            raise LookupFailed(f"HTTP {status}")
        try:
            found = json.loads(data) if data else []
        except ValueError:
            raise LookupFailed("bad JSON")
        if not isinstance(found, list):
            raise LookupFailed("unexpected reply")
        return {p["name"].lower() for p in found if isinstance(p, dict) and "name" in p}

    taken = set()
    for name in names:
        status, data = _request("GET", endpoint.url.format(quote(name)))
        if status == 429:
            raise RateLimited()
        if status == 200 and data.strip():
            taken.add(name.lower())
        elif status in (200, 204, 404):
            pass                                    # no such player -> free
        else:
            raise LookupFailed(f"HTTP {status}")
    return taken


VALID_NAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")


def check_one(name, endpoints=None):
    """Look up one name right now. True = available. Raises LookupFailed if no server answers."""
    last = None
    for ep in endpoints or default_endpoints():
        try:
            return name.lower() not in lookup(ep, [name])
        except (RateLimited, LookupFailed) as e:
            last = e
    raise LookupFailed(f"no Mojang server answered ({type(last).__name__} {last or ''})".strip())


# --------------------------------------------------------------------------- locked-name check
#
# The public lookups above only say whether an ACCOUNT has a name. A name nobody
# has can still be locked: blocked by Mojang, or on hold after someone changed
# away from it. Only Mojang's logged-in check (the one the launcher uses when you
# change your name) can tell, so it needs the user's Minecraft access token.

PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
CLAIM_URL = "https://api.minecraftservices.com/minecraft/profile/name/{}/available"

CLAIMABLE = "AVAILABLE"      # really free - you can take it
HELD = "DUPLICATE"           # nobody shows up with it, yet it isn't free -> on hold
BLOCKED = "NOT_ALLOWED"      # Mojang doesn't allow this name
UNVERIFIED = "UNVERIFIED"    # couldn't run the locked check (no/expired login)


def clean_token(token):
    """Accept a token pasted with quotes, spaces or a 'Bearer ' prefix."""
    token = (token or "").strip().strip('"').strip("'").strip()
    if token.lower().startswith("bearer "):
        token = token[7:].strip()
    return token


def _auth(token):
    return {"Authorization": "Bearer " + token}


def token_owner(token):
    """Name of the Minecraft profile the token belongs to ('' if it has none yet)."""
    status, data = _request("GET", PROFILE_URL, extra_headers=_auth(token))
    if status in (401, 403):
        raise AuthFailed()
    if status == 429:
        raise RateLimited()
    if status == 404:
        return ""
    if status != 200:
        raise LookupFailed(f"HTTP {status}")
    try:
        return json.loads(data).get("name", "")
    except ValueError:
        raise LookupFailed("bad JSON")


def check_claimable(name, token):
    """CLAIMABLE, HELD or BLOCKED. Raises AuthFailed / RateLimited / LookupFailed."""
    status, data = _request("GET", CLAIM_URL.format(quote(name)), extra_headers=_auth(token))
    if status in (401, 403):
        raise AuthFailed()
    if status == 429:
        raise RateLimited()
    if status != 200:
        raise LookupFailed(f"HTTP {status}")
    try:
        result = str(json.loads(data).get("status", "")).upper()
    except (ValueError, AttributeError):
        raise LookupFailed("bad JSON")
    if result not in (CLAIMABLE, HELD, BLOCKED):
        raise LookupFailed(f"unknown answer {result!r}")
    return result


class LockChecker:
    """
    Background queue that runs the locked check on names that look free.
    Mojang allows far fewer of these than normal lookups, so it runs at its
    own (self-tuning) pace and checks the shortest names first.

    on_result(name, verdict) gets CLAIMABLE / HELD / BLOCKED / UNVERIFIED.
    on_auth_failed() is called once if the token stops working; everything
    still waiting is then reported as UNVERIFIED.
    """

    def __init__(self, token, on_result, on_status=None, on_auth_failed=None):
        self.token = token
        self.on_result = on_result
        self.on_status = on_status or (lambda s: None)
        self.on_auth_failed = on_auth_failed or (lambda: None)
        self.endpoint = Endpoint("locked check", CLAIM_URL, "auth", 1, start_rate=1.0, max_rate=5.0)
        self._heap = []
        self._seq = itertools.count()
        self._cv = threading.Condition()
        self._busy = 0
        self._stop = False
        self.dead = False
        self._thread = threading.Thread(target=self._run, name="lock-checker", daemon=True)
        self._thread.start()

    def submit(self, name):
        with self._cv:
            if self.dead or self._stop:
                self.on_result(name, UNVERIFIED)
                return
            heapq.heappush(self._heap, (len(name), next(self._seq), name))
            self._cv.notify()

    def pending(self):
        with self._cv:
            return len(self._heap) + self._busy

    def accepting(self):
        with self._cv:
            return not (self.dead or self._stop)

    def is_alive(self):
        return self._thread.is_alive()

    def stop(self):
        """Stop now; names still waiting are reported as UNVERIFIED."""
        with self._cv:
            self._stop = True
            self._cv.notify_all()

    def _drain_unverified(self):
        with self._cv:
            rest = [n for _, _, n in sorted(self._heap)]
            self._heap.clear()
        for n in rest:
            self.on_result(n, UNVERIFIED)

    def _wait_until(self, t):
        while True:
            with self._cv:
                if self._stop:
                    return False
                d = t - time.monotonic()
                if d <= 0:
                    return True
                self._cv.wait(min(d, 0.25))

    def _run(self):
        failures = 0
        while True:
            with self._cv:
                while not self._heap and not self._stop:
                    self._cv.wait(0.5)
                if self._stop:
                    break
                _, _, name = heapq.heappop(self._heap)
                self._busy = 1
            verdict = None
            try:
                if not self._wait_until(self.endpoint.reserve()):
                    verdict = UNVERIFIED
                else:
                    verdict = check_claimable(name, self.token)
                    self.endpoint.success()
                    failures = 0
            except RateLimited:
                wait = self.endpoint.rate_limited()
                self.on_status(f"Locked check: Mojang asked us to slow down - pausing {wait}s")
                with self._cv:                       # try this name again later
                    heapq.heappush(self._heap, (len(name), next(self._seq), name))
            except AuthFailed:
                with self._cv:
                    self.dead = True
                    self._stop = True
                verdict = UNVERIFIED
                self.on_auth_failed()
            except LookupFailed as e:
                failures += 1
                if failures >= 3:
                    verdict = UNVERIFIED
                    failures = 0
                else:
                    self.endpoint.failed()
                    self.on_status(f"Locked check: {e} - retrying")
                    with self._cv:
                        heapq.heappush(self._heap, (len(name), next(self._seq), name))
            finally:
                with self._cv:
                    self._busy = 0
            if verdict:
                self.on_result(name, verdict)
        self._drain_unverified()


# --------------------------------------------------------------------------- engine

class Checker:
    """
    Runs the check in background threads.

    on_result(name, available: bool) and on_status(text) are called from
    worker threads - the GUI must hand them to its own thread.
    """

    def __init__(self, names, threads=8, on_result=None, on_status=None,
                 endpoints=None, charset=None, smooth=True):
        self._names = iter(names)
        self.threads = max(1, int(threads))
        self.on_result = on_result or (lambda n, a: None)
        self.on_status = on_status or (lambda s: None)
        self.endpoints = endpoints or default_endpoints()
        for ep in self.endpoints:
            ep.smooth = smooth
        self.charset = charset or (LETTERS + DIGITS + UNDERSCORE)

        self._lock = threading.Lock()
        self._retry = collections.deque()
        self._in_flight = set()
        self._exhausted = False
        self._last_given = None
        self._stop = threading.Event()
        self._workers = []
        self._recent = collections.deque()          # (time, count) for speed
        self._fail_streak = 0

        self.checked = 0
        self.available = 0
        self.taken = 0
        self.started_at = None

    # ---- public
    def start(self):
        self.started_at = time.monotonic()
        for i in range(self.threads):
            t = threading.Thread(target=self._worker, name=f"checker-{i}", daemon=True)
            t.start()
            self._workers.append(t)

    def stop(self):
        self._stop.set()

    def is_running(self):
        return any(t.is_alive() for t in self._workers)

    def join(self, timeout=None):
        for t in self._workers:
            t.join(timeout)

    def finished_all(self):
        with self._lock:
            return self._exhausted and not self._retry and not self._in_flight

    def speed(self):
        """Names per second over the last ~5 seconds."""
        now = time.monotonic()
        with self._lock:
            while self._recent and now - self._recent[0][0] > 5:
                self._recent.popleft()
            total = sum(c for _, c in self._recent)
        span = min(5.0, now - (self.started_at or now)) or 1.0
        return total / span

    def resume_point(self):
        """Earliest name (in generation order) that has not been finished."""
        with self._lock:
            pending = list(self._in_flight) + list(self._retry)
            if pending:
                return min(pending, key=lambda n: name_order_key(n, self.charset))
            return None if self._exhausted else self._last_given

    # ---- internals
    def _take(self, n):
        with self._lock:
            out = []
            while self._retry and len(out) < n:
                out.append(self._retry.popleft())
            if len(out) < n and not self._exhausted:
                more = list(itertools.islice(self._names, n - len(out)))
                if len(more) < n - len(out):
                    self._exhausted = True
                out.extend(more)
                if more:
                    self._last_given = more[-1]
            self._in_flight.update(out)
            return out

    def _give_back(self, names):
        with self._lock:
            self._in_flight.difference_update(names)
            self._retry.extendleft(reversed(names))

    def _done(self, names):
        with self._lock:
            self._in_flight.difference_update(names)

    def _pick_endpoint(self):
        """Prefer bulk servers; use single-name ones only when all bulk ones are busy."""
        bulk = [e for e in self.endpoints if e.kind == "bulk"]
        single = [e for e in self.endpoints if e.kind != "bulk"]
        best_bulk = min(bulk, key=Endpoint.ready_at) if bulk else None
        best_single = min(single, key=Endpoint.ready_at) if single else None
        if best_bulk is None:
            return best_single
        if best_single is None:
            return best_bulk
        now = time.monotonic()
        if best_bulk.ready_at() - now > 1.0 and best_single.ready_at() < best_bulk.ready_at():
            return best_single
        return best_bulk

    def _sleep_until(self, t):
        while not self._stop.is_set():
            d = t - time.monotonic()
            if d <= 0:
                return True
            self._stop.wait(min(d, 0.25))
        return False

    def _worker(self):
        while not self._stop.is_set():
            ep = self._pick_endpoint()
            names = self._take(ep.batch)
            if not names:
                if self.finished_all():
                    return
                self._stop.wait(0.2)
                continue

            # wait for our slot (and for any pause this server got meanwhile)
            if not (self._sleep_until(ep.reserve()) and self._sleep_until(ep.cooldown_until)):
                self._give_back(names)
                return

            try:
                taken = lookup(ep, names)
            except RateLimited:
                self._give_back(names)
                wait = ep.rate_limited()
                self.on_status(f"{ep.label} asked us to slow down - pausing it {wait}s, "
                               f"other servers keep going")
                continue
            except LookupFailed as e:
                self._give_back(names)
                wait = ep.failed()
                with self._lock:
                    self._fail_streak += 1
                    streak = self._fail_streak
                if streak >= 8:
                    self.on_status("Can't reach Mojang's servers - check your internet. Retrying...")
                else:
                    self.on_status(f"{ep.label}: {e} - retrying in {wait}s")
                continue

            ep.success()
            with self._lock:
                self._fail_streak = 0
            n_avail = 0
            for name in names:
                is_free = name.lower() not in taken
                n_avail += is_free
                self.on_result(name, is_free)
            with self._lock:
                self.checked += len(names)
                self.available += n_avail
                self.taken += len(names) - n_avail
                self._recent.append((time.monotonic(), len(names)))
            self._done(names)
