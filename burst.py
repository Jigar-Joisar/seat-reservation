#!/usr/bin/env python3
"""On-sale stampede for the seat-reservation service. Stdlib only.

usage: ./burst.py <BASE_URL> [--requests 20000] [--users 2000] [--admin-secret S]
Exits non-zero if any correctness check fails.
"""
import argparse, collections, http.client, json, os, random, re, ssl, sys, threading, time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

local = threading.local()


class Client:
    def __init__(self, base):
        u = urlparse(base)
        self.https = u.scheme == "https"
        self.host, self.port = u.hostname, u.port or (443 if self.https else 80)

    def _conn(self):
        c = getattr(local, "c", None)
        if c is None:
            c = (http.client.HTTPSConnection(self.host, self.port, timeout=120, context=ssl.create_default_context())
                 if self.https else http.client.HTTPConnection(self.host, self.port, timeout=120))
            local.c = c
        return c

    def call(self, method, path, token=None, body=None, headers=None):
        h = {"Content-Type": "application/json"}
        if token: h["Authorization"] = "Bearer " + token
        h.update(headers or {})
        data = json.dumps(body) if body is not None else None
        for attempt in range(3):
            try:
                c = self._conn()
                c.request(method, path, data, h)
                r = c.getresponse()
                raw = r.read().decode()
                try: js = json.loads(raw)
                except ValueError: js = {"raw": raw}
                return r.status, js, raw
            except (http.client.HTTPException, OSError):
                local.c = None
                if attempt == 2: return 599, {"error": "transport"}, ""
        return 599, {}, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base_url")
    ap.add_argument("--requests", type=int, default=20000)
    ap.add_argument("--users", type=int, default=2000)
    ap.add_argument("--seats", type=int, default=500)
    ap.add_argument("--hot", type=int, default=5, help="number of hot seats")
    ap.add_argument("--workers", type=int, default=200)
    ap.add_argument("--admin-secret", default=os.environ.get("ADMIN_SECRET", "dev-admin-secret"))
    a = ap.parse_args()
    api = Client(a.base_url.rstrip("/"))
    fails = []
    RUN = "%x" % random.getrandbits(32)
    ALL = []

    def check(ok, msg):
        print(("  PASS  " if ok else "  FAIL  ") + msg)
        if not ok: fails.append(msg)

    def metrics():
        s, _, raw = api.call("GET", "/actuator/prometheus")
        out = {}
        for line in raw.splitlines():
            m = re.match(r'^(reservations_\w+)(\{reason="(\w+)"[^}]*\})?\s+([0-9.eE+-]+)$', line)
            if m: out[m.group(1) + (":" + m.group(3) if m.group(3) else "")] = float(m.group(4))
        return out

    print(f"== target {a.base_url}")
    for _ in range(60):  # tolerate cold start
        s, _, _ = api.call("GET", "/health/ready")
        if s == 200: break
        time.sleep(2)
    check(s == 200, "readiness /health/ready == 200")
    if s != 200: sys.exit(1)

    s, js, _ = api.call("POST", "/auth/token", body={"user_id": "burst-admin", "admin_secret": a.admin_secret})
    if s != 200: print("cannot get admin token", s, js); sys.exit(1)
    admin = js["token"]

    pool = ThreadPoolExecutor(max_workers=a.workers)
    tokens = {}

    def tok(u):
        if u not in tokens:
            tokens[u] = api.call("POST", "/auth/token", body={"user_id": u})[1]["token"]
        return tokens[u]

    def new_show(n, limit=None):
        body = {"name": "burst", "seats": [f"A{i}" for i in range(1, n + 1)], "price_paise": 25000}
        if limit: body["per_user_limit"] = limit
        s, js, _ = api.call("POST", "/shows", admin, body)
        assert s == 201, (s, js)
        return js["id"]

    def reserve(show, user, seats, key):
        s, js, _ = api.call("POST", f"/shows/{show}/reserve", tok(user), {"seats": seats, "idempotency_key": key})
        ALL.append((s, js))
        return s, js

    def tally(results):
        dist = collections.Counter()
        for s, js in results:
            if s in (200, 201): dist["201 confirmed" if s == 201 else "200 idempotent-replay"] += 1
            elif s == 409: dist["409 " + js.get("error", "?")] += 1
            else: dist[f"{s} {js.get('error', '')}".strip()] += 1
        return dist

    def five_xx(dist): return sum(v for k, v in dist.items() if k[0] == "5")

    m0 = metrics()

    # 1. hot seat storm
    print("\n== 1. hot-seat storm: 500 distinct users, one seat (A1)")
    show = new_show(100)
    users = [f"hot-{RUN}-{i}" for i in range(500)]
    list(pool.map(tok, users))
    res = list(pool.map(lambda u: reserve(show, u, ["A1"], "k-" + u), users))
    d = tally(res)
    print("  ", dict(d))
    check(d["201 confirmed"] == 1, "exactly one 201 for the hot seat")
    check(d["409 seat_taken"] == 499, "other 499 got 409 seat_taken")
    check(five_xx(d) == 0, "zero 5xx")

    # 2. stampede
    print(f"\n== 2. stampede: {a.requests} requests, {a.users} users, {a.seats} seats, {a.hot} hot seats, ~10% same-key retries")
    show2 = new_show(a.seats)
    seat_names = [f"A{i}" for i in range(1, a.seats + 1)]
    hot = seat_names[:a.hot]
    ids = [f"u{RUN}-{i}" for i in range(a.users)]
    list(pool.map(tok, ids))
    rnd = random.Random(7)
    jobs = []
    for i in range(a.requests):
        u = rnd.choice(ids)
        r = rnd.random()
        seats = [rnd.choice(hot)] if r < 0.6 else [rnd.choice(seat_names)] if r < 0.9 else rnd.sample(seat_names, 2)
        jobs.append((u, seats, f"{u}-{i}"))
    for k in range(len(jobs) // 10):  # retries: same user+key+seats re-sent
        jobs.append(jobs[rnd.randrange(len(jobs) // 10 * 9)])
    rnd.shuffle(jobs)

    stop = threading.Event()
    inv_violations, inv_samples = [], [0]

    def poll():
        while not stop.is_set():
            s, js, _ = api.call("GET", f"/shows/{show2}")
            if s == 200:
                inv_samples[0] += 1
                if js["available"] + js["held"] + js["confirmed"] != js["total_seats"]: inv_violations.append(js)
            time.sleep(0.05)
    th = threading.Thread(target=poll); th.start()
    t0 = time.time()
    out = list(pool.map(lambda j: (j, reserve(show2, *j)), jobs))
    dt = time.time() - t0
    stop.set(); th.join()
    d = tally([r for _, r in out])
    print(f"   {len(jobs)} requests in {dt:.1f}s ({len(jobs)/dt:.0f} req/s)")
    for k, v in sorted(d.items()): print(f"   {v:>7}  {k}")
    check(five_xx(d) == 0, "zero 5xx during the stampede")
    final = api.call("GET", f"/shows/{show2}")[1]
    check(final["available"] + final["held"] + final["confirmed"] == final["total_seats"], f"final invariant: {final['available']}+{final['held']}+{final['confirmed']} == {final['total_seats']}")
    check(not inv_violations, f"invariant held on all {inv_samples[0]} live samples during the burst")
    winners = collections.defaultdict(set)
    per_user = collections.defaultdict(set)
    for (u, seats, key), (s, js) in out:
        if s in (200, 201):
            for st in js["seats"]: winners[st].add(js["user_id"]); per_user[js["user_id"]].add(st)
            check_user = js["user_id"] == u
            if not check_user: check(False, "identity mismatch in response")
    check(all(len(v) == 1 for v in winners.values()), "no seat confirmed to more than one user")
    check(len(winners) == final["confirmed"], f"distinct seats won ({len(winners)}) == confirmed in show state ({final['confirmed']})")
    check(max((len(v) for v in per_user.values()), default=0) <= 4, "no user holds more than 4 seats")
    retried = sum(1 for _, (s, js) in out if s == 200)
    created = d["201 confirmed"]
    check(sum(len(v) for v in per_user.values()) == final["confirmed"], "sum of per-user seats == confirmed seats")
    print(f"   hot seats {hot}: winners = { {h: list(winners.get(h, [])) for h in hot} }")

    # 3. idempotency
    print("\n== 3. idempotency")
    show3 = new_show(20)
    same = list(pool.map(lambda _: reserve(show3, f"idem-{RUN}", ["A7"], "one-key"), range(50)))
    d = tally(same); print("  ", dict(d))
    check(d["201 confirmed"] == 1 and len({js["reservation_id"] for s, js in same if s in (200, 201)}) == 1, "50 parallel same-key requests -> exactly one reservation")
    check(reserve(show3, f"idem-{RUN}", ["A8"], "one-key")[0] == 409, "same key, different seats -> 409")
    check(api.call("GET", f"/shows/{show3}")[1]["confirmed"] == 1, "retries moved nothing extra")

    # 4. per-user limit
    print("\n== 4. per-user limit (limit=4, 10 parallel reserves by one user)")
    show4 = new_show(30)
    lim = list(pool.map(lambda i: reserve(show4, f"greedy-{RUN}", [f"A{i+1}"], f"g{i}"), range(10)))
    d = tally(lim); print("  ", dict(d))
    check(d["201 confirmed"] == 4 and d["409 per_user_limit"] == 6, "exactly 4 confirmed, 6 per_user_limit")

    # 5. cancel + rebook
    print("\n== 5. cancel / rebook / ownership")
    show5 = new_show(5)
    s, r1 = reserve(show5, f"own-{RUN}", ["A1"], "c1")
    rid = r1["reservation_id"]
    check(api.call("POST", f"/reservations/{rid}/cancel", tok(f"thief-{RUN}"))[0] == 403, "non-owner cancel -> 403")
    s, js, _ = api.call("POST", "/shows/%s/reserve" % show5, tok(f"spoofer-{RUN}"), {"seats": ["A2"], "idempotency_key": "sp", "user_id": "someone-else"})
    ALL.append((s, js))
    check(s == 201 and js["user_id"] == f"spoofer-{RUN}", "spoofed user_id in body ignored")
    check(api.call("POST", f"/reservations/{rid}/cancel", tok(f"own-{RUN}"))[0] == 200, "owner cancel -> 200")
    check(reserve(show5, f"rebooker-{RUN}", ["A1"], "rb")[0] == 201, "released seat is re-bookable")
    api.call("POST", f"/reservations/{rid}/cancel", tok(f"own-{RUN}"))
    st = {x["seat"]: x["status"] for x in api.call("GET", f"/shows/{show5}")[1]["seats"]}
    check(st["A1"] == "confirmed", "stale cancel did not resurrect seat confirmed to someone else")

    # 5b. holds
    print("\n== 5b. timed holds: storm, confirm, ownership")
    show6 = new_show(5)
    hu = [f"hold-{RUN}-{i}" for i in range(200)]
    list(pool.map(tok, hu))
    def hold(u):
        s, js, _ = api.call("POST", f"/shows/{show6}/hold", tok(u), {"seats": ["A1"], "idempotency_key": "h-" + u})
        if s != 201: ALL.append((s, js))  # declines count toward metrics; 201 holds are held, not confirmed
        return s, js
    hr = list(pool.map(hold, hu))
    d = tally(hr); print("  ", dict(d))
    check(sum(1 for s, _ in hr if s == 201) == 1 and five_xx(d) == 0, "200 users hold one seat -> exactly one 201, zero 5xx")
    won = next(js for s, js in hr if s == 201)
    check(won["status"] == "held" and "expires_at" in won, "hold returns status=held with expires_at")
    other = next(u for (s, _), u in zip(hr, hu) if s != 201)
    check(api.call("POST", f"/reservations/{won['reservation_id']}/confirm", tok(other))[0] == 403, "non-owner confirm -> 403")
    check(api.call("POST", f"/reservations/{won['reservation_id']}/confirm", tok(won["user_id"]))[1].get("status") == "confirmed", "owner confirm -> confirmed")
    sh = api.call("GET", f"/shows/{show6}")[1]
    check(sh["confirmed"] == 1 and sh["available"] + sh["held"] + sh["confirmed"] == sh["total_seats"], "show state consistent after hold+confirm")

    # 6. metrics reconcile
    print("\n== 6. metrics reconciliation (assumes no other traffic hit the service meanwhile)")
    time.sleep(0.6)
    m1 = metrics()
    obs = tally(ALL)
    dc = m1.get("reservations_confirmed_total", 0) - m0.get("reservations_confirmed_total", 0)
    check(dc == obs["201 confirmed"] + 1, f"confirmed counter delta ({dc:.0f}) == observed reserve 201s + 1 confirmed hold ({obs['201 confirmed']}+1)")
    for r, key in [("seat_taken", "409 seat_taken"), ("per_user_limit", "409 per_user_limit"), ("idempotent_replay", "200 idempotent-replay"),
                   ("idempotency_conflict", "409 idempotency_conflict"), ("contention", "409 contention"), ("invalid_seat", "400 invalid_seat")]:
        dm = m1.get("reservations_declined_total:" + r, 0) - m0.get("reservations_declined_total:" + r, 0)
        check(dm == obs[key], f"declined[{r}] delta ({dm:.0f}) == observed ({obs[key]})")
    g = api.call("GET", "/actuator/prometheus")[2]
    mm = re.search(r'seats_available\{show_id="%s"[^}]*\}\s+([0-9.]+)' % show2, g)
    check(mm and float(mm.group(1)) == final["available"], f"seats_available gauge for stampede show == API ({mm.group(1) if mm else None})")

    print("\n== SUMMARY")
    print("   overall outcome distribution:", dict(tally(ALL)))
    if fails:
        print(f"   {len(fails)} CHECK(S) FAILED"); sys.exit(1)
    print("   ALL CHECKS PASSED")


if __name__ == "__main__":
    main()
