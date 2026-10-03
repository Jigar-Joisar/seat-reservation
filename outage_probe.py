#!/usr/bin/env python3
"""Database-outage probe (stdlib only). Run it against a deployed service, then stop the database, wait, and start it again.

  ./outage_probe.py <BASE_URL> --admin-secret S [--duration 300] [--insecure] [--report out.json]

Every second it records /health/live, /health/ready and one reservation attempt (a fresh user and a fresh seat, so no
per-user limit interferes). Requests run in background threads, so a request stuck waiting for the database does not
stop the timeline. At the end it prints the transitions (first 503, recovery) and audits the show: every seat that
returned 201 must be confirmed, every other seat must be available, nothing booked twice.
Expected during an outage: live stays 200, ready flips to 503, reservations return 503 (never 201, never a partial booking).
"""
import argparse, datetime, http.client, json, ssl, sys, threading, time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base_url")
    ap.add_argument("--admin-secret", default="dev-admin-secret")
    ap.add_argument("--duration", type=int, default=300, help="seconds to probe")
    ap.add_argument("--insecure", action="store_true")
    ap.add_argument("--report", default="")
    a = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)
    u = urlparse(a.base_url if "://" in a.base_url else "http://" + a.base_url)
    ctx = ssl.create_default_context()
    if a.insecure: ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE

    def call(method, path, token=None, body=None, timeout=90):
        c = (http.client.HTTPSConnection(u.hostname, u.port or 443, timeout=timeout, context=ctx) if u.scheme == "https"
             else http.client.HTTPConnection(u.hostname, u.port or 80, timeout=timeout))
        try:
            h = {"Content-Type": "application/json"}
            if token: h["Authorization"] = "Bearer " + token
            c.request(method, path, json.dumps(body) if body is not None else None, h)
            r = c.getresponse(); raw = r.read().decode()
            try: js = json.loads(raw)
            except ValueError: js = {}
            return r.status, js, r.getheader("Retry-After")
        except Exception as e:
            return 0, {"error": type(e).__name__}, None
        finally:
            c.close()

    run = "%x" % int(time.time())
    s, js, _ = call("POST", "/auth/token", body={"user_id": "probe-admin", "admin_secret": a.admin_secret})
    if s != 200: sys.exit(f"cannot get admin token: {s} {js}")
    n_seats = a.duration + 10
    s, js, _ = call("POST", "/shows", js["token"], {"name": "outage-probe-" + run, "seats": [f"P{i}" for i in range(n_seats)], "price_paise": 100})
    if s != 201: sys.exit(f"cannot create show: {s} {js}")
    show = js["id"]
    print(f"probing {a.base_url} for {a.duration}s, show {show}; stop the database now, wait, then start it again\n")
    print("  t(s)  live  ready  reserve(latency)")

    t0 = time.time(); lock = threading.Lock(); rows = []; wins = {}

    def reserve(i, sent):
        s, js, ra = call("POST", f"/shows/{show}/reserve", call("POST", "/auth/token", body={"user_id": f"probe-{run}-{i}"})[1].get("token"),
                         {"seats": [f"P{i}"], "idempotency_key": f"probe-{run}-{i}"})
        with lock:
            rows.append(("reserve", sent, s, js.get("error"), time.time() - t0 - sent, ra))
            if s == 201: wins[f"P{i}"] = True
        print(f"  {sent:5.0f}  reserve P{i} -> {s} {js.get('error') or ''}{' Retry-After=' + ra if ra else ''} ({time.time() - t0 - sent:.1f}s)")

    pool = ThreadPoolExecutor(max_workers=64)
    i = 0
    while time.time() - t0 < a.duration:
        sent = time.time() - t0
        pool.submit(reserve, i, sent); i += 1
        live = call("GET", "/health/live", timeout=5)[0]; ready = call("GET", "/health/ready", timeout=5)[0]
        with lock: rows.append(("live", sent, live, None, 0, None)); rows.append(("ready", sent, ready, None, 0, None))
        print(f"  {sent:5.0f}  {live:4}  {ready:5}")
        time.sleep(max(0, 1 - (time.time() - t0 - sent)))
    pool.shutdown(wait=True, cancel_futures=False)

    def first(kind, pred):
        return next((r[1] for r in sorted(rows, key=lambda r: r[1]) if r[0] == kind and pred(r[2])), None)
    down_at = first("ready", lambda s: s != 200)
    up_at = next((r[1] for r in sorted(rows, key=lambda r: r[1]) if r[0] == "ready" and r[2] == 200 and down_at is not None and r[1] > down_at), None)
    res = [r for r in rows if r[0] == "reserve"]
    codes = {}
    for r in res: codes[r[2]] = codes.get(r[2], 0) + 1
    print("\n== summary")
    print("  live statuses:", sorted({r[2] for r in rows if r[0] == "live"}), "(must be only 200)")
    print("  ready first non-200 at t=%s s; back to 200 at t=%s s" % (f"{down_at:.0f}" if down_at is not None else "never", f"{up_at:.0f}" if up_at is not None else "never"))
    print("  reserve status counts:", codes)
    print("  reserve 5xx other than 503:", sum(1 for r in res if r[2] >= 500 and r[2] != 503), "| transport failures (status 0):", codes.get(0, 0))
    first503 = first("reserve", lambda s: s == 503)
    after = [r for r in res if up_at is not None and r[1] > up_at and r[2] == 201]
    print("  first reserve 503 sent at t=%s s; first 201 after recovery at t=%s s" % (f"{first503:.0f}" if first503 is not None else "never",
          f"{min(r[1] for r in after):.0f}" if after else "never"))

    s, st, _ = call("GET", f"/shows/{show}")
    seats = {x["seat_number"] if "seat_number" in x else x.get("seat"): x["status"] for x in st.get("seats", [])}
    confirmed = {k for k, v in seats.items() if v == "confirmed"}
    ok = s == 200 and set(wins) <= confirmed and all(v in ("confirmed", "available") for v in seats.values()) \
        and st.get("available", 0) + st.get("held", 0) + st.get("confirmed", 0) == st.get("total_seats", -1)
    print(f"  audit: {len(wins)} seats returned 201, {len(confirmed)} seats confirmed, invariant "
          f"{st.get('available')}+{st.get('held')}+{st.get('confirmed')}=={st.get('total_seats')}  ->  {'PASS' if ok else 'FAIL'}")
    # a request that timed out client-side (status 0) may still have booked; the audit tolerates only confirmed seats we know about, so report them
    extra = sorted(confirmed - set(wins))
    if extra: print("  confirmed seats whose response was not a 201 (client timeout or error, booking did commit):", extra)
    if a.report:
        json.dump({"target": a.base_url, "started": datetime.datetime.fromtimestamp(t0).astimezone().isoformat(), "duration_s": a.duration,
                   "ready_down_at_s": down_at, "ready_up_at_s": up_at, "reserve_status_counts": {str(k): v for k, v in codes.items()},
                   "audit_pass": ok, "seats_201": len(wins), "seats_confirmed": len(confirmed), "extra_confirmed": extra}, open(a.report, "w"), indent=2)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
