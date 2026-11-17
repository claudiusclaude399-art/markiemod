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

def build_charset(letters=True, digits=True, underscore=False):
    cs = ""
    if letters:
        cs += LETTERS
    if digits:
        cs += DIGITS
    if underscore:
        cs += UNDERSCORE
    return cs


def count_names(min_len, max_len, charset):
    return sum(len(charset) ** n for n in range(min_len, max_len + 1))


def generate_names(min_len, max_len, charset, start=None, shuffle=False):
    """Yield every name with a length in [min_len, max_len] built from charset."""
    for length in range(min_len, max_len + 1):
        if shuffle:
            for idx in _random_permutation(len(charset) ** length):
                yield _index_to_name(idx, length, charset)
            continue
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
            self.rate = min(self.max_rate, self.rate + 1.0 / self.rate)   # ~ +1 req/s each second

    def rate_limited(self):
        with self.lock:
            now = time.monotonic()
            if now < self.cooldown_until:
                # a request that was already on its way when we paused - same strike
                return round(self.cooldown_until - now, 1)
            self.strikes += 1
            self.rate = max(0.5, self.rate * 0.7)               # back off
            wait = min(0.5 * 2 ** (self.strikes - 1), 120)      # 0.5, 1, 2 ... 120 s
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


def _request(method, url, body=None):
    parts = urlsplit(url)
    path = parts.path + (("?" + parts.query) if parts.query else "")
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
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


# --------------------------------------------------------------------------- engine

class Checker:
    """
    Runs the check in background threads.

    on_result(name, available: bool) and on_status(text) are called from
    worker threads - the GUI must hand them to its own thread.
    """

    def __init__(self, names, threads=8, on_result=None, on_status=None,
                 endpoints=None, charset=None):
        self._names = iter(names)
        self.threads = max(1, int(threads))
        self.on_result = on_result or (lambda n, a: None)
        self.on_status = on_status or (lambda s: None)
        self.endpoints = endpoints or default_endpoints()
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
