#!/usr/bin/env python3
"""Adversarial load / correctness suite for the seat-reservation service. Python 3 stdlib only.

usage:  ./burst.py <BASE_URL> [--scale 1.0] [--admin-secret S] [--only 2,5,8]

Everything is verified from the OUTSIDE (HTTP only): outcome distributions, the show invariant, a full audit that
cross-checks every reservation against the seat map, per-user limits, latency, readiness during load and metric
reconciliation. Exits non-zero if any check fails.

Hold-expiry scenarios need a short TTL; start the server with e.g.  HOLD_TTL_SECONDS=3 ./run.sh
(they are skipped, with a message, when the TTL is long).
"""
import argparse, collections, datetime, http.client, json, os, random, re, ssl, sys, threading, time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

local = threading.local()
TRANSPORT_ERRORS = []
LAST_ERROR = [""]


def make_ssl_context(insecure=False):
    """Default context; falls back to the OS CA bundle (python.org builds on macOS ship without one)."""
    if insecure:
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    ctx = ssl.create_default_context()
    if not ssl.get_default_verify_paths().cafile and not os.environ.get("SSL_CERT_FILE"):
        for cand in ("/etc/ssl/cert.pem", "/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt"):
            if os.path.exists(cand):
                return ssl.create_default_context(cafile=cand)
    return ctx


class Client:
    def __init__(self, base, insecure=False):
        self.insecure = insecure
        if "://" not in base: base = "http://" + base
        u = urlparse(base)
        self.https = u.scheme == "https"
        self.host, self.port = u.hostname, u.port or (443 if self.https else 80)

    def _conn(self):
        c = getattr(local, "c", None)
        if c is None:
            c = (http.client.HTTPSConnection(self.host, self.port, timeout=120, context=make_ssl_context(self.insecure))
                 if self.https else http.client.HTTPConnection(self.host, self.port, timeout=120))
            local.c = c
        return c

    def call(self, method, path, token=None, body=None, headers=None, raw_body=None):
        h = {"Content-Type": "application/json"}
        if token: h["Authorization"] = "Bearer " + token
        h.update(headers or {})
        data = raw_body if raw_body is not None else (json.dumps(body) if body is not None else None)
        retryable = method == "GET" or path == "/auth/token"  # token minting is side-effect free; other POSTs are never blindly retried (outcome unknown)
        for attempt in range(3 if retryable else 1):
            try:
                c = self._conn()
                c.request(method, path, data, h)
                r = c.getresponse()
                raw = r.read().decode()
                try: js = json.loads(raw)
                except ValueError: js = {"raw": raw}
                return r.status, js, raw
            except (http.client.HTTPException, OSError) as e:
                local.c = None
                LAST_ERROR[0] = f"{type(e).__name__}: {e}"
                if attempt == (2 if retryable else 0):
                    TRANSPORT_ERRORS.append(method + " " + path)
                    return 599, {"error": "transport"}, ""
        return 599, {}, ""


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base_url")
    ap.add_argument("--scale", type=float, default=1.0, help="multiply all request volumes (0.2 = quick smoke run)")
    ap.add_argument("--workers", type=int, default=200)
    ap.add_argument("--admin-secret", default=os.environ.get("ADMIN_SECRET", "dev-admin-secret"))
    ap.add_argument("--only", default="", help="comma separated scenario numbers to run (default: all)")
    ap.add_argument("--hold-ttl", type=float, default=None, help="override auto-detected hold TTL in seconds")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--insecure", action="store_true", help="skip TLS certificate verification (last resort for broken local CA bundles)")
    a = ap.parse_args()
    a.base_url = a.base_url.rstrip("/")
    api = Client(a.base_url, a.insecure)
    only = {int(x) for x in a.only.split(",") if x.strip()}
    RUN = "%x" % random.getrandbits(32)
    S = lambda n: max(1, int(n * a.scale))
    fails, results = [], []
    OUT = []          # (kind, status, json) for every reserve/hold -> metrics reconciliation
    LAT = []          # seconds per reserve/hold call
    EXTRA = collections.Counter()   # confirm transitions, confirm hold_expired declines
    lock = threading.Lock()
    pool = ThreadPoolExecutor(max_workers=a.workers)
    tokens = {}

    def check(ok, msg):
        print(("  PASS  " if ok else "  FAIL  ") + msg)
        if not ok:
            fails.append(msg)
            if cur["n"]: results[-1][2] += 1
        return ok

    def tok(u):
        if u not in tokens:
            s, js, _ = api.call("POST", "/auth/token", body={"user_id": u})
            tokens[u] = js["token"]
        return tokens[u]

    def new_show(n, limit=None):
        body = {"name": "burst-" + RUN, "seats": [f"A{i}" for i in range(1, n + 1)], "price_paise": 25000}
        if limit: body["per_user_limit"] = limit
        s, js, _ = api.call("POST", "/shows", admin, body)
        assert s == 201, (s, js)
        return js["id"]

    def place(show, user, seats, key, hold=False, owned=None, extra=None):
        t = time.time()
        s, js, _ = api.call("POST", f"/shows/{show}/{'hold' if hold else 'reserve'}", tok(user), {"seats": seats, "idempotency_key": key, **(extra or {})})
        dt = time.time() - t
        with lock:
            OUT.append(("hold" if hold else "reserve", s, js))
            LAT.append(dt)
            if owned is not None and s in (200, 201): owned[js["reservation_id"]] = user
        return s, js

    def confirm(rid, user):
        s, js, _ = api.call("POST", f"/reservations/{rid}/confirm", tok(user))
        with lock:
            if s == 200: EXTRA["confirm_ok"] += 1
            elif s == 409 and js.get("error") == "hold_expired": EXTRA["hold_expired"] += 1
        return s, js

    def cancel(rid, user):
        return api.call("POST", f"/reservations/{rid}/cancel", tok(user))[:2]

    def state(show):
        return api.call("GET", f"/shows/{show}")[1]

    def dist(results_):
        d = collections.Counter()
        for s, js in results_:
            if s == 201: d["201 created"] += 1
            elif s == 200: d["200 replay/ok"] += 1
            elif s == 409: d["409 " + js.get("error", "?")] += 1
            else: d[f"{s} {js.get('error', '')}".strip()] += 1
        return d

    def show_dist(d):
        for k, v in sorted(d.items()): print(f"   {v:>7}  {k}")

    def five(d): return sum(v for k, v in d.items() if k[0] in "56")

    def audit(show, owned, label):
        """Cross-check every known reservation (via the API) against the seat map."""
        st = state(show)
        infos = list(pool.map(lambda kv: api.call("GET", f"/reservations/{kv[0]}", tok(kv[1]))[1], owned.items()))
        live = [r for r in infos if r.get("status") in ("held", "confirmed")]
        owner, dup = {}, []
        for r in live:
            for sname in r["seats"]:
                if sname in owner: dup.append(sname)
                owner[sname] = r["status"]
        per_user = collections.Counter(r["user_id"] for r in live for _ in r["seats"])
        taken = {x["seat"]: x["status"] for x in st["seats"] if x["status"] != "available"}
        check(st["available"] + st["held"] + st["confirmed"] == st["total_seats"], f"[{label}] invariant {st['available']}+{st['held']}+{st['confirmed']} == {st['total_seats']}")
        check(not dup, f"[{label}] no seat appears in two live reservations")
        check(set(taken) == set(owner), f"[{label}] seat map ({len(taken)} taken) == union of live reservations ({len(owner)})")
        check(all(taken.get(x) == v for x, v in owner.items()), f"[{label}] every seat's status matches its reservation's status")
        check(max(per_user.values(), default=0) <= st["per_user_limit"], f"[{label}] no user above limit {st['per_user_limit']} (max {max(per_user.values(), default=0)})")
        return st, live

    cur = {"n": None}

    def scenario(n, title):
        if only and n not in only: return False
        print(f"\n== {n}. {title}")
        cur["n"] = n
        results.append([n, title, 0])
        return True

    # ------------------------------------------------------------------ setup
    print(f"== target {a.base_url}   run={RUN} scale={a.scale}")
    s = 0
    for _ in range(60):
        s, _, _ = api.call("GET", "/health/ready")
        if s == 200: break
        time.sleep(2)
    check(s == 200, "readiness /health/ready == 200")
    if s != 200:
        print(f"   last transport error: {LAST_ERROR[0] or 'none (service answered but not 200)'}")
        if "CERTIFICATE_VERIFY_FAILED" in LAST_ERROR[0]: print("   TLS verification failed: install your OS/Python CA certificates, set SSL_CERT_FILE, or pass --insecure")
        sys.exit(1)
    s, js, _ = api.call("POST", "/auth/token", body={"user_id": "burst-admin", "admin_secret": a.admin_secret})
    if s != 200: print("cannot get admin token", s, js); sys.exit(1)
    admin = js["token"]

    def metrics():
        s, _, raw = api.call("GET", "/actuator/prometheus")
        out = {}
        for line in raw.splitlines():
            m = re.match(r'^(reservations_\w+)(\{reason="(\w+)"[^}]*\})?\s+([0-9.eE+-]+)$', line)
            if m: out[m.group(1) + (":" + m.group(3) if m.group(3) else "")] = float(m.group(4))
        return out
    m0 = metrics()

    ttl = a.hold_ttl
    if ttl is None:
        probe = new_show(1)
        s, js = place(probe, f"ttl-{RUN}", ["A1"], "p", hold=True)
        if s == 201 and js.get("expires_at"):
            iso = re.sub(r"(\.\d{6})\d+", r"\1", js["expires_at"]).replace("Z", "+00:00")
            ttl = (datetime.datetime.fromisoformat(iso) - datetime.datetime.now(datetime.timezone.utc)).total_seconds()
    short_ttl = ttl is not None and ttl <= 30
    print(f"   hold TTL ~ {ttl:.0f}s -> expiry scenarios {'ENABLED' if short_ttl else 'SKIPPED (start the server with HOLD_TTL_SECONDS=3 to enable)'}" if ttl else "   could not detect hold TTL")
    wait_sweep = (ttl or 0) + 2.5

    # ------------------------------------------------------------------ 1
    if scenario(1, "hot-seat storm: 500 distinct users, one seat"):
        show = new_show(100)
        users = [f"hot-{RUN}-{i}" for i in range(500)]
        list(pool.map(tok, users))
        owned = {}
        res = list(pool.map(lambda u: place(show, u, ["A1"], "k-" + u, owned=owned), users))
        d = dist(res); show_dist(d)
        check(d["201 created"] == 1, "exactly one 201 for the hot seat")
        check(d["409 seat_taken"] == 499, "other 499 got 409 seat_taken")
        check(five(d) == 0, "zero 5xx")
        audit(show, owned, "storm")

    # ------------------------------------------------------------------ 2
    if scenario(2, f"on-sale stampede: {S(20000)} requests, 2000 users, 500 seats, 5 hot seats, ~10% same-key retries"):
        show2 = new_show(500)
        names = [f"A{i}" for i in range(1, 501)]
        hot = names[:5]
        ids = [f"u{RUN}-{i}" for i in range(2000)]
        list(pool.map(tok, ids))
        rnd = random.Random(a.seed)
        jobs = []
        for i in range(S(20000)):
            u = rnd.choice(ids)
            r = rnd.random()
            seats = [rnd.choice(hot)] if r < 0.6 else [rnd.choice(names)] if r < 0.9 else rnd.sample(names, 2)
            jobs.append((u, seats, f"{u}-{i}"))
        retries = [jobs[rnd.randrange(len(jobs))] for _ in range(len(jobs) // 10)]
        jobs = jobs + retries
        rnd.shuffle(jobs)
        stop = threading.Event()
        bad_inv, bad_ready, samples = [], [], [0, 0]

        def poll():
            while not stop.is_set():
                s_, js_, _ = api.call("GET", f"/shows/{show2}")
                if s_ == 200:
                    samples[0] += 1
                    if js_["available"] + js_["held"] + js_["confirmed"] != js_["total_seats"]: bad_inv.append(js_)
                r_ = api.call("GET", "/health/ready")[0]
                samples[1] += 1
                if r_ != 200: bad_ready.append(r_)
                time.sleep(0.05)
        th = threading.Thread(target=poll); th.start()
        lat0 = len(LAT)
        owned = {}
        t0 = time.time()
        out = list(pool.map(lambda j: (j, place(show2, j[0], j[1], j[2], owned=owned)), jobs))
        dt = time.time() - t0
        stop.set(); th.join()
        d = dist([r for _, r in out])
        print(f"   {len(jobs)} requests in {dt:.1f}s ({len(jobs)/dt:.0f} req/s)   latency ms  p50={pct(LAT[lat0:],.5)*1000:.0f} p95={pct(LAT[lat0:],.95)*1000:.0f} p99={pct(LAT[lat0:],.99)*1000:.0f} max={max(LAT[lat0:])*1000:.0f}")
        show_dist(d)
        check(five(d) == 0, "zero 5xx during the stampede")
        check(not bad_inv, f"invariant held on all {samples[0]} live samples during the burst")
        check(not bad_ready, f"/health/ready stayed 200 on all {samples[1]} probes during the burst")
        check(all(js["user_id"] == u for (u, _, _), (s_, js) in out if s_ in (200, 201)), "every success is attributed to the token's user")
        st, live = audit(show2, owned, "stampede")
        if a.scale >= 1: check(st["confirmed"] == 500, f"hall sold out exactly: confirmed {st['confirmed']} == 500")
        else: check(0 < st["confirmed"] <= 500, f"confirmed {st['confirmed']} within capacity 500")
        for h in hot:
            w = {r["user_id"] for r in live if h in r["seats"]}
            check(len(w) == 1, f"hot seat {h} has exactly one owner")

    # ------------------------------------------------------------------ 3
    if scenario(3, "idempotency: duplicate storm, same key/different seats, cross-user keys"):
        show3 = new_show(20)
        same = list(pool.map(lambda _: place(show3, f"idem-{RUN}", ["A7"], "one-key"), range(50)))
        d = dist(same); show_dist(d)
        check(d["201 created"] == 1 and len({js["reservation_id"] for s_, js in same if s_ in (200, 201)}) == 1, "50 parallel same-key requests -> exactly one reservation")
        check(place(show3, f"idem-{RUN}", ["A8"], "one-key")[0] == 409, "same key, different seats -> 409")
        check(state(show3)["confirmed"] == 1, "retries moved nothing extra")
        # many users, each firing 3 identical parallel requests with the same key
        show3b = new_show(300, limit=4)
        users = [f"dup-{RUN}-{i}" for i in range(150)]
        list(pool.map(tok, users))
        owned = {}
        jobs = [(u, [f"A{(i * 2) + 1}", f"A{(i * 2) + 2}"], f"dk-{u}") for i, u in enumerate(users) for _ in range(3)]
        random.Random(1).shuffle(jobs)
        res = list(pool.map(lambda j: (j, place(show3b, j[0], j[1], j[2], owned=owned)), jobs))
        by_user = collections.defaultdict(set)
        for (u, _, _), (s_, js) in res:
            if s_ in (200, 201): by_user[u].add(js["reservation_id"])
        d = dist([r for _, r in res]); show_dist(d)
        check(d["201 created"] == 150 and d["200 replay/ok"] == 300, "150 users x3 identical parallel requests -> 150 created + 300 replays")
        check(all(len(v) == 1 for v in by_user.values()) and len(by_user) == 150, "each user's duplicates all returned the same reservation")
        check(state(show3b)["confirmed"] == 300, "exactly 300 seats taken (no double charge)")
        # same key string used by different users is independent
        show3c = new_show(40)
        res = list(pool.map(lambda i: place(show3c, f"shared-{RUN}-{i}", [f"A{i+1}"], "SAME-KEY"), range(30)))
        check(all(s_ == 201 for s_, _ in res) and len({js['reservation_id'] for _, js in res}) == 30, "same key string across 30 different users -> 30 independent reservations")

    # ------------------------------------------------------------------ 4
    if scenario(4, "per-user limit under concurrency"):
        show4 = new_show(30)
        lim = list(pool.map(lambda i: place(show4, f"greedy-{RUN}", [f"A{i+1}"], f"g{i}"), range(10)))
        d = dist(lim); show_dist(d)
        check(d["201 created"] == 4 and d["409 per_user_limit"] == 6, "one user, 10 parallel reserves, limit 4 -> exactly 4 created, 6 per_user_limit")
        mix = list(pool.map(lambda i: place(show4, f"mixed-{RUN}", [f"A{i+11}"], f"m{i}", hold=bool(i % 2)), range(10)))
        check(dist(mix)["201 created"] == 4, "mixed reserve+hold by one user also capped at 4")
        n_users, per = S(150), 8
        show4b = new_show(n_users * per + 10)
        users = [f"lim-{RUN}-{i}" for i in range(n_users)]
        list(pool.map(tok, users))
        owned = {}
        jobs = [(u, [f"A{i * per + k + 1}"], f"l-{u}-{k}") for i, u in enumerate(users) for k in range(per)]
        random.Random(2).shuffle(jobs)
        res = list(pool.map(lambda j: (j, place(show4b, j[0], j[1], j[2], owned=owned)), jobs))
        wins = collections.Counter(j[0] for j, (s_, _) in res if s_ == 201)
        d = dist([r for _, r in res]); show_dist(d)
        check(five(d) == 0, "zero 5xx")
        check(all(wins[u] == 4 for u in users), f"{n_users} users x {per} parallel distinct seats (plenty free) -> every user got exactly 4")
        audit(show4b, owned, "limit stampede")

    # ------------------------------------------------------------------ 5
    if scenario(5, "multi-seat deadlock storm: crossed seat orders, all-or-nothing"):
        show5 = new_show(12, limit=4)
        users = [f"ms-{RUN}-{i}" for i in range(S(300))]
        list(pool.map(tok, users))
        owned = {}
        rnd = random.Random(5)
        jobs = []
        for i, u in enumerate(users):
            seats = rnd.sample([f"A{k}" for k in range(1, 13)], rnd.randint(2, 4))
            jobs.append((u, seats, f"k{i}"))
        res = list(pool.map(lambda j: (j, place(show5, j[0], j[1], j[2], owned=owned)), jobs))
        d = dist([r for _, r in res]); show_dist(d)
        check(all(s_ in (201, 409) for _, (s_, _) in res), "only 201/409 outcomes (no deadlock errors, no 5xx)")
        won = sum(len(js["seats"]) for _, (s_, js) in res if s_ == 201)
        st, _ = audit(show5, owned, "deadlock storm")
        check(st["confirmed"] == won, f"seats taken ({st['confirmed']}) == seats in winning requests ({won}): failed requests leaked nothing")

    # ------------------------------------------------------------------ 6
    if scenario(6, "sell-out: demand far above supply, every seat sold exactly once"):
        show6 = new_show(200)
        users = [f"so-{RUN}-{i}" for i in range(600)]
        list(pool.map(tok, users))
        rnd = random.Random(6)
        owned = {}
        jobs = [(rnd.choice(users), [f"A{rnd.randint(1, 200)}"], f"s{i}") for i in range(S(6000))]
        res = list(pool.map(lambda j: (j, place(show6, j[0], j[1], j[2], owned=owned)), jobs))
        d = dist([r for _, r in res]); show_dist(d)
        check(five(d) == 0, "zero 5xx")
        st, _ = audit(show6, owned, "sell-out")
        if a.scale >= 1: check(st["confirmed"] == 200 and st["available"] == 0, f"sold out: confirmed {st['confirmed']} == 200, available {st['available']} == 0")
        check(d["201 created"] == st["confirmed"], f"number of 201s ({d['201 created']}) == seats confirmed ({st['confirmed']}): no lost or duplicated sale")

    # ------------------------------------------------------------------ 7
    if scenario(7, "seat recycling: 3 seats, reserve/cancel churn by 200 users"):
        show7 = new_show(3, limit=4)
        users = [f"rc-{RUN}-{i}" for i in range(S(200))]
        list(pool.map(tok, users))
        cnt = collections.Counter()

        def churn(u):
            for rd in range(6):
                s_, js = place(show7, u, ["A1"], f"c{rd}")
                if s_ == 201:
                    cnt["win"] += 1
                    if cancel(js["reservation_id"], u)[0] == 200: cnt["cancel"] += 1
                elif s_ != 409: cnt["bad"] += 1
        list(pool.map(churn, users))
        print(f"   wins={cnt['win']} cancels={cnt['cancel']} unexpected={cnt['bad']}")
        check(cnt["bad"] == 0 and cnt["win"] == cnt["cancel"] and cnt["win"] > 0, "every win was cancelled cleanly, no unexpected statuses")
        st = state(show7)
        check(st["available"] == 3, "all seats back to available after the churn")

    # ------------------------------------------------------------------ 8
    if scenario(8, "chaos mix: reserve / hold / confirm / cancel / replay / reads by 100 users"):
        show8 = new_show(60, limit=3)
        users = [f"ch-{RUN}-{i}" for i in range(100)]
        list(pool.map(tok, users))
        owned = {}
        bad = []
        allowed = {200, 201, 409}

        def chaos(u):
            rnd = random.Random(a.seed * 100003 + int(u.rsplit('-', 1)[-1]))
            mine, holds, sent, done = [], [], [], set()
            for i in range(S(80)):
                d = rnd.random()
                if d < 0.40 or not mine and d < 0.85:
                    seats = rnd.sample([f"A{k}" for k in range(1, 61)], rnd.randint(1, 3))
                    key = f"r{i}"
                    sent.append((key, seats, False))
                    s_, js = place(show8, u, seats, key, owned=owned)
                    if s_ == 201: mine.append(js["reservation_id"])
                elif d < 0.55:
                    seats = rnd.sample([f"A{k}" for k in range(1, 61)], rnd.randint(1, 2))
                    key = f"h{i}"
                    sent.append((key, seats, True))
                    s_, js = place(show8, u, seats, key, hold=True, owned=owned)
                    if s_ == 201: mine.append(js["reservation_id"]); holds.append(js["reservation_id"])
                elif d < 0.68:
                    todo = [r for r in holds if r not in done]
                    if not todo: continue
                    rid = rnd.choice(todo); done.add(rid)
                    s_, js = confirm(rid, u)
                elif d < 0.80:
                    rid = rnd.choice(mine)
                    s_, js = cancel(rid, u)
                elif d < 0.90 and sent:
                    key, seats, hd = rnd.choice(sent)
                    s_, js = place(show8, u, seats, key, hold=hd, owned=owned)
                else:
                    s_, js = api.call("GET", f"/shows/{show8}")[:2]
                    if js["available"] + js["held"] + js["confirmed"] != js["total_seats"]: bad.append(("invariant", js))
                    continue
                if s_ not in allowed: bad.append((s_, js))
        list(pool.map(chaos, users))
        check(not bad, f"every response in the chaos run was 200/201/409 (no 5xx, no surprises){'' if not bad else ' e.g. ' + str(bad[:2])}")
        if short_ttl:
            print(f"   waiting {wait_sweep:.1f}s for outstanding holds to expire...")
            time.sleep(wait_sweep)
        st, _ = audit(show8, owned, "chaos")
        if short_ttl: check(st["held"] == 0, "all unconfirmed holds expired and were released")

    # ------------------------------------------------------------------ 9
    if scenario(9, "confirm vs cancel race on the same hold (30 rounds)"):
        show9 = new_show(30, limit=1)
        outcomes = collections.Counter()
        bad = 0
        for i in range(1, 31):
            u = f"race-{RUN}-{i}"
            s_, js = place(show9, u, [f"A{i}"], "k", hold=True)
            rid = js["reservation_id"]
            r = list(pool.map(lambda k: confirm(rid, u) if k == 0 else cancel(rid, u), range(2)))
            outcomes[(r[0][0], r[1][0])] += 1
            final = api.call("GET", f"/reservations/{rid}", tok(u))[1]["status"]
            if final != "cancelled" or r[1][0] != 200 or r[0][0] not in (200, 409): bad += 1
        print(f"   (confirm,cancel) status pairs: {dict(outcomes)}")
        check(bad == 0, "cancel always wins the final state, confirm is 200 or 409, never a mixed result")
        check(state(show9)["available"] == 30, "all 30 seats available afterwards")

    # ------------------------------------------------------------------ 10
    if scenario(10, "hold expiry (needs a short TTL)"):
        if not short_ttl:
            print("   SKIPPED: hold TTL is long. Restart the server with HOLD_TTL_SECONDS=3 to run this scenario.")
        else:
            show10 = new_show(24, limit=1)
            users = [f"ex-{RUN}-{i}" for i in range(1, 25)]
            rids = {}
            for i, u in enumerate(users, 1):
                s_, js = place(show10, u, [f"A{i}"], "k", hold=True)
                rids[u] = js["reservation_id"]
            keep_u = users[0]
            check(confirm(rids[keep_u], keep_u)[0] == 200, "one hold confirmed immediately")
            results_ = {}

            def late_confirm(u):
                time.sleep(max(0, ttl - 0.4) + random.random() * 1.0)
                results_[u] = confirm(rids[u], u)[0]
            list(pool.map(late_confirm, users[1:]))
            time.sleep(wait_sweep)
            ok = 0
            for u in users[1:]:
                st_ = api.call("GET", f"/reservations/{rids[u]}", tok(u))[1]["status"]
                if results_[u] == 200:
                    ok += 1
                    if st_ != "confirmed": check(False, f"confirm returned 200 but reservation is {st_} (seat lost!)")
                elif st_ != "expired": check(False, f"rejected confirm but reservation is {st_}")
            check(True, f"{ok} late confirms won the race, {23 - ok} lost it; every confirmed hold stayed confirmed, every other one expired")
            st = state(show10)
            check(st["confirmed"] == 1 + ok and st["held"] == 0 and st["available"] == 23 - ok, f"seat map matches: confirmed {st['confirmed']}, held {st['held']}, available {st['available']}")
            late = users[-1]
            if results_[late] == 409:
                check(place(show10, late, [f"A{len(users)}"], "again")[0] == 201, "an expired hold frees the user's limit and the seat can be booked again")
            time.sleep(wait_sweep)
            check(state(show10)["confirmed"] >= 1, "a confirmed seat survives later expiry sweeps")

    # ------------------------------------------------------------------ 11
    if scenario(11, "hostile input and bad credentials under load"):
        show11 = new_show(10)
        before = state(show11)
        hostile_user = f"hostile-{RUN}"
        good = tok(hostile_user)
        flip = good[:-2] + ("AA" if not good.endswith("AA") else "BB")
        body_ok = {"seats": ["A1"], "idempotency_key": "x"}
        cases = [
            ("no token", lambda: api.call("POST", f"/shows/{show11}/reserve", None, body_ok), {401}),
            ("garbage token", lambda: api.call("POST", f"/shows/{show11}/reserve", "garbage", body_ok), {401}),
            ("tampered token", lambda: api.call("POST", f"/shows/{show11}/reserve", flip, body_ok), {401}),
            ("user token creating show", lambda: api.call("POST", "/shows", good, {"name": "x", "seats": ["A1"], "price_paise": 1}), {403}),
            ("malformed json", lambda: api.call("POST", f"/shows/{show11}/reserve", good, raw_body="{not json"), {400}),
            ("empty body", lambda: api.call("POST", f"/shows/{show11}/reserve", good, raw_body=""), {400}),
            ("wrong types", lambda: api.call("POST", f"/shows/{show11}/reserve", good, raw_body='{"seats":"A1","idempotency_key":5}'), {400}),
            ("missing key", lambda: api.call("POST", f"/shows/{show11}/reserve", good, {"seats": ["A1"]}), {400}),
            ("duplicate seats", lambda: api.call("POST", f"/shows/{show11}/reserve", good, {"seats": ["A1", "A1"], "idempotency_key": "d"}), {400}),
            # edge proxies (e.g. Render) may block SQL-looking payloads with their own 403 before the app sees them; either rejection is fine
            ("injection-ish seat", lambda: api.call("POST", f"/shows/{show11}/reserve", good, {"seats": ["A1'; DROP TABLE seats;--"], "idempotency_key": "i"}), {400, 403}),
            ("unknown show", lambda: api.call("POST", "/shows/nope/reserve", good, body_ok), {404}),
            ("fractional price", lambda: api.call("POST", "/shows", admin, raw_body='{"name":"x","seats":["A1"],"price_paise":99.5}'), {400}),
            ("giant price", lambda: api.call("POST", "/shows", admin, {"name": "x", "seats": ["A1"], "price_paise": 9223372036854775807}), {400}),
            ("unknown route", lambda: api.call("GET", "/does/not/exist"), {404}),
            ("wrong method", lambda: api.call("DELETE", f"/shows/{show11}"), {405}),
        ]
        jobs = [c for c in cases for _ in range(S(60))]
        random.Random(11).shuffle(jobs)
        res = list(pool.map(lambda c: (c[0], c[2], c[1]()[0]), jobs))
        bad = collections.Counter((n, s_) for n, exp, s_ in res if s_ not in exp)
        check(not bad, f"{len(res)} hostile requests all got their expected 4xx{'' if not bad else ': ' + str(dict(bad))}")
        check(not any(s_ >= 500 for _, _, s_ in res), "zero 5xx for hostile input")
        unk = list(pool.map(lambda i: place(show11, hostile_user, [f"Z{i}"], f"u{i}")[0], range(S(60))))
        check(all(s_ == 400 for s_ in unk), "unknown seats -> 400 invalid_seat")
        after = state(show11)
        check(after["available"] == before["available"] == 10, "none of it changed the show")

    # ------------------------------------------------------------------ 12
    if scenario(12, "cross-show isolation: same users, same seat names, two shows at once"):
        sa, sb = new_show(10), new_show(10)
        users = [f"iso-{RUN}-{i}" for i in range(200)]
        list(pool.map(tok, users))
        oa, ob = {}, {}
        jobs = [(show, u, oa if show == sa else ob) for u in users for show in (sa, sb)]
        random.Random(12).shuffle(jobs)
        res = list(pool.map(lambda j: (j[0], place(j[0], j[1], ["A1"], f"iso-{j[0][:6]}", owned=j[2])), jobs))
        for show, label in ((sa, "show A"), (sb, "show B")):
            w = sum(1 for sh, (s_, _) in res if sh == show and s_ == 201)
            check(w == 1, f"{label}: exactly one winner for A1")
        audit(sa, oa, "show A"); audit(sb, ob, "show B")
        u = users[0]
        check(state(sa)["confirmed"] == 1 and state(sb)["confirmed"] == 1, "each show sold exactly 1 seat; the shows did not affect each other")

    # ------------------------------------------------------------------ 13
    if scenario(13, "ownership, spoofing and cancel safety"):
        show13 = new_show(5)
        own, thief, spoofer, rebook = (f"{n}-{RUN}" for n in ("own", "thief", "spoofer", "rebook"))
        s_, r1 = place(show13, own, ["A1"], "c1")
        rid = r1["reservation_id"]
        check(api.call("POST", f"/reservations/{rid}/cancel", tok(thief))[0] == 403, "non-owner cancel -> 403")
        check(api.call("GET", f"/reservations/{rid}", tok(thief))[0] == 403, "non-owner read -> 403")
        s_, js = place(show13, spoofer, ["A2"], "sp", extra={"user_id": own})
        check(s_ == 201 and js["user_id"] == spoofer, "spoofed user_id in body ignored")
        check(api.call("POST", f"/reservations/{rid}/cancel", tok(own))[0] == 200, "owner cancel -> 200")
        check(place(show13, rebook, ["A1"], "rb")[0] == 201, "released seat is re-bookable")
        cancel(rid, own)
        st13 = {x["seat"]: x["status"] for x in state(show13)["seats"]}
        check(st13["A1"] == "confirmed", "stale cancel did not resurrect a seat confirmed to someone else")
        s_, h = place(show13, own, ["A3"], "h", hold=True)
        check(confirm(h["reservation_id"], own)[0] == 200 and api.call("POST", f"/reservations/{h['reservation_id']}/confirm", tok(own))[0] == 200, "confirm is idempotent")

    # ------------------------------------------------------------------ 14
    cur["n"] = None
    print("\n== metrics reconciliation (assumes no other traffic hit the service meanwhile)")
    time.sleep(0.6)
    m1 = metrics()
    delta = lambda k: m1.get(k, 0) - m0.get(k, 0)
    created_res = sum(1 for k, s_, _ in OUT if k == "reserve" and s_ == 201)
    created_hold = sum(1 for k, s_, _ in OUT if k == "hold" and s_ == 201)
    check(delta("reservations_confirmed_total") == created_res + EXTRA["confirm_ok"], f"confirmed counter delta ({delta('reservations_confirmed_total'):.0f}) == reserve 201s ({created_res}) + hold confirmations ({EXTRA['confirm_ok']})")
    check(delta("reservations_held_total") == created_hold, f"held counter delta ({delta('reservations_held_total'):.0f}) == hold 201s ({created_hold})")
    obs = collections.Counter()
    for k, s_, js in OUT:
        if s_ == 200: obs["idempotent_replay"] += 1
        elif s_ == 409: obs[js.get("error")] += 1
        elif s_ == 400 and js.get("error") == "invalid_seat": obs["invalid_seat"] += 1
    obs["hold_expired"] += EXTRA["hold_expired"]
    for r in ["seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict", "contention", "invalid_seat", "hold_expired"]:
        check(delta("reservations_declined_total:" + r) == obs[r], f"declined[{r}] delta ({delta('reservations_declined_total:' + r):.0f}) == observed ({obs[r]})")

    check(not TRANSPORT_ERRORS, f"no transport-level failures ({len(TRANSPORT_ERRORS)}){'' if not TRANSPORT_ERRORS else ' e.g. ' + str(TRANSPORT_ERRORS[:3])}")
    print("\n== SUMMARY")
    print(f"   total reserve/hold calls: {len(OUT)}   overall latency ms  p50={pct(LAT,.5)*1000:.0f} p95={pct(LAT,.95)*1000:.0f} p99={pct(LAT,.99)*1000:.0f}")
    print("   overall outcomes:", dict(dist([(s_, js) for _, s_, js in OUT])))
    for n, title, f in results: print(f"   [{'FAIL' if f else 'ok  '}] {n}. {title}" + (f"  ({f} failed checks)" if f else ""))
    if fails:
        print(f"\n   {len(fails)} CHECK(S) FAILED"); sys.exit(1)
    print("\n   ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
