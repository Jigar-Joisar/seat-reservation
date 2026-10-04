# Live test report

Evidence that the deployed service behaves correctly, gathered on 2026-10-03 against the public deployment. It includes the runs that went wrong, the diagnosis, and what was not tested.

| | |
|---|---|
| Target | https://seat-reservation-9zhl.onrender.com |
| Platform | Render, Docker web service, **free** instance, single instance |
| Database | Render managed PostgreSQL (free), pool of 16 connections |
| Deployed code | `6d1d9b7` (PostgreSQL support) for the first runs, `260c303` (adds the 503 fail-closed mapping) for the later runs; later commits change only the burst tool and docs |
| Raw artifacts | everything referenced below is in [`docs/evidence/`](evidence/) |

## Summary

* **Correctness held in every run.** Across the runs in the table below (about 152,000 reserve/hold calls) no seat was ever sold twice, no user exceeded the limit, no idempotent retry booked twice, and every client-observed outcome equalled the server's Prometheus counters. **The application produced no 5xx in any load run**: the server-side 5xx series is empty after every run. The only 5xx-class responses were the deliberate `503` during the outage test, Render's `502`s during that outage, and one `520` in the full-size run (see below).
* **The scale-0.5 suite has passed cleanly twice** (run 2 and run 3, the latter on the current build with the circuit breaker live; it never opened under load). Run 2 (19,779 calls, 650 s, 98 checks, 0 failed), and run 3 (19,788 calls, 665 s, all checks). The first run had **one unexplained transport failure** (a request that got no response and timed out client-side), reported below as a failed run.
* **The full-size suite (scale 1.0) passed cleanly on run 2** (37,808 calls in 1,227 s, all checks, zero 5xx, zero transport failures) on the build with the Tomcat keep-alive fix. Run 1, on the older build, had one failure: a single `520` out of 6,000 sell-out responses. The application recorded no 5xx and the sell-out was exact; it is reported as a failed run, not a pass.
* **Two single requests were lost between the client and the application** on the pre-fix build (one in the first scale-0.5 run, one `520` in the first full-size run). Neither has recurred in the three large runs since the keep-alive change (~77,000 calls). The cause is still not established, but the evidence is now consistent with a proxy/keep-alive race that the Tomcat tuning eliminated.
* **A database outage is survived without data damage, and the corrected build is verified live.** In run 4 liveness stayed `200`, the breaker opened within 8 s, 62 reservations failed fast with `503` + `Retry-After` and none slower than 0.4 s, nothing was booked, the audit passed, and the service recovered by itself. The only imperfection was one `500` on a request already inside its transaction when the database vanished; that exception is now also mapped to `503`. See *Database outage test, run 4*.
* **Throughput of the free instance is about 25-35 requests/s.**

## Runs

| Started | Scale | Hold TTL | Calls | Duration | Latency | Result | Notes |
|---|---|---|---|---|---|---|---|
| 2026-10-03 18:06 IST | 0.2 | 8 s | 8,922 | 374 s | p50 3.7 / p99 19.0 s | PASS | all 13 scenarios; hold expiry exercised (0 of 23 timed confirms won the race at ~4 s latency) |
| 2026-10-03 19:38 IST | 0.15 | 300 s | 7,045 | 279 s | p50 3.9 / p99 19.0 s | PASS | scenario 10 skipped (long TTL) |
| 2026-10-03 20:16 IST | 0.5 | 300 s | 19,767 | 844 s | p50 3.9 / p99 20.0 s | **FAIL** | one transport failure in scenario 7, see below |
| 2026-10-03 20:57 IST | 0.5, scenario 7 only | 300 s | 601 | 95 s | p50 4.2 / p99 16.7 s | PASS | rerun of the failed scenario in isolation (terminal output only) |
| 2026-10-03 21:01 IST | 0.5 | 300 s | 19,779 | 650 s | p50 3.6 / p99 20.8 s | PASS | **the reference run**; details below |
| 2026-10-04 15:12 IST | 0.5 | 300 s | 19,788 | 665 s | p50 3.8 / p99 19.7 s | PASS | build `4e5f0d2`: circuit breaker, keep-alive tuning and scheduler fix live; breaker never opened, no lost request |
| 2026-10-04 15:38 IST | 1.0 (full size), run 2 | 300 s | 37,808 | 1,227 s | p50 4.2 / p99 19.6 s | PASS | build `4e5f0d2`: keep-alive tuning live; all checks, zero 5xx, zero transport failures, breaker never opened |
| 2026-10-03 21:38 IST | 1.0 (full size) | 300 s | 37,854 | 1,270 s | p50 3.9 / p99 21.1 s | **FAIL** | one `520` response in scenario 6; server-side clean, see below |

Artifacts: [scale 0.2, 8 s TTL](evidence/burst-live-2026-10-03.txt) ([json](evidence/burst-live-2026-10-03.json), [metrics](evidence/metrics-live-after-burst.prom)) · [scale 0.15](evidence/burst-live-s015-2026-10-03.txt) · [scale 0.5 run 1, failed](evidence/burst-live-s050-run1-FAILED-2026-10-03.txt) ([metrics](evidence/metrics-after-s050-run1.prom)) · [scale 0.5 run 2](evidence/burst-live-s050-run2-2026-10-03.txt) ([json](evidence/burst-live-s050-run2-2026-10-03.json), [metrics](evidence/metrics-after-s050-run2.prom)) · [scale 1.0, failed](evidence/burst-live-s100-FAILED-2026-10-03.txt) ([json](evidence/burst-live-s100-FAILED-2026-10-03.json), [metrics](evidence/metrics-after-s100.prom)) · [scale 0.5 run 3](evidence/burst-live-s050-run3-2026-10-04.txt) ([json](evidence/burst-live-s050-run3-2026-10-04.json), [metrics](evidence/metrics-after-s050-run3.prom)) · [scale 1.0 run 2](evidence/burst-live-s100-run2-2026-10-04.txt) ([json](evidence/burst-live-s100-run2-2026-10-04.json), [metrics](evidence/metrics-after-s100-run2.prom)).

## Reference run: scale 0.5, run 2

19,779 reserve/hold calls in 650 s, single process, no other traffic. **All 13 scenarios passed: 98 checks, 0 failed, 0 responses with 5xx status, 0 transport failures.**

### Outcome distribution (reserve and hold calls)

| Outcome | Count |
|---|---|
| `201 created` | 1350 |
| `409 seat_taken` | 17207 |
| `200 replay/ok` | 456 |
| `409 per_user_limit` | 735 |
| `409 idempotency_conflict` | 1 |
| `400 invalid_seat` | 30 |

### Latency (client side, per reserve/hold call)

| p50 | p95 | p99 |
|---|---|---|
| 3602 ms | 14075 ms | 20795 ms |

The 11,000-call stampede alone ran at 35 requests/s with p50 4.3 s, p99 22.8 s and a maximum of 50 s. Requests queue; they do not fail.

### Per-scenario timing

| # | Scenario | Seconds | Calls | Result |
|---|---|---|---|---|
| 1 | hot-seat storm | 22.9 | 500 | PASS |
| 2 | on-sale stampede | 340.1 | 11000 | PASS |
| 3 | idempotency | 14.4 | 531 | PASS |
| 4 | per-user limit under concurrency | 24.2 | 620 | PASS |
| 5 | multi-seat deadlock storm | 5.9 | 150 | PASS |
| 6 | sell-out | 92.2 | 3000 | PASS |
| 7 | seat recycling | 18.2 | 600 | PASS |
| 8 | chaos mix | 96.9 | 2913 | PASS |
| 9 | confirm vs cancel race on the same hold (30 rounds) | 11.6 | 30 | PASS |
| 10 | hold expiry (needs a short TTL) | 0.0 | 0 | PASS |
| 11 | hostile input and bad credentials under load | 7.5 | 30 | PASS |
| 12 | cross-show isolation | 13.3 | 400 | PASS |
| 13 | ownership, spoofing and cancel safety | 1.3 | 4 | PASS |

### Metric reconciliation

Prometheus counters from `/actuator/prometheus` after the run against what the client observed (the harness asserts every line; the service had no other traffic).

| Metric | Server | Client observed | Match |
|---|---|---|---|
| `reservations_confirmed_total` | 1319 | 1289 reserve `201` + 30 hold confirmations | yes |
| `reservations_held_total` | 61 | 61 hold `201` | yes |
| `reservations_declined_total{reason="seat_taken"}` | 17207 | 17207 | yes |
| `reservations_declined_total{reason="per_user_limit"}` | 735 | 735 | yes |
| `reservations_declined_total{reason="idempotent_replay"}` | 456 | 456 | yes |
| `reservations_declined_total{reason="idempotency_conflict"}` | 1 | 1 | yes |
| `reservations_declined_total{reason="invalid_seat"}` | 30 | 30 | yes |
| `reservations_declined_total{reason="hold_expired"}` | 18 | 18 | yes |
| `reservations_declined_total{reason="contention"}` | 0 | 0 | yes |
| `http_server_requests_seconds_count{status=~"5.."}` | no series | 0 | yes |
| `hikaricp_connections_timeout_total` | 0 | n/a | yes |

The `seats_*` gauges satisfy `available + held + confirmed == total` for every show (asserted on every poll during the stampede and in the final audit of each scenario).

## The failed run (scale 0.5, run 1)

Everything passed except one request. In scenario 7 (seat recycling) a single `POST /shows/{id}/reserve` received no response, the client gave up at its 120 s read timeout and recorded a transport failure. Scenario 7 took 128.8 s instead of 6-20 s, and two checks failed (`every win was cancelled cleanly, no unexpected statuses` and `no transport-level failures`).

What the evidence shows:

* The service produced no 5xx, no `contention` and no Hikari connection timeout; process uptime was continuous through the run.
* All server counters matched the client's observations exactly. If the service had completed the lost request, a counter would be off by one, so it never completed it.
* The affected show ended with all 3 seats `available` and 11 wins matched by 11 cancels: the lost request left nothing behind.
* A rerun of scenario 7 alone passed (38 s, no failures), and run 2 of the full suite passed with scenario 7 taking 18 s.

What is **not** known: whether the request never reached the application (for example a keep-alive connection silently dropped by Render's edge, so the read waits until the timeout) or stalled inside it. The no-held-connection evidence argues against a database stall, but this was not proven. The burst client now records the exception type, elapsed time and connection idle time for any transport failure, so a recurrence will identify itself. One failure in two full 0.5 runs is reported as it happened; the problem is neither declared fixed nor reproduced.

## Full-size run 2 (scale 1.0, the clean run)

Ran on build `4e5f0d2` (Tomcat keep-alive 300 s, unlimited requests per connection). 37,808 calls in 1,227 s: every scenario passed, every counter reconciled, zero 5xx client-side and server-side, zero transport failures, the breaker never opened, and the sell-out was again exact (500/500). The single `520` of run 1 did not recur. The honest reading: the keep-alive change is *consistent with* the lost-request fix but not proven - the failure was rare (2 in ~77,000 calls) and its cause was never identified. Artifacts: [txt](evidence/burst-live-s100-run2-2026-10-04.txt), [json](evidence/burst-live-s100-run2-2026-10-04.json), [metrics](evidence/metrics-after-s100-run2.prom).

## Full-size run 1 (scale 1.0, failed)

37,854 reserve/hold calls in 1270 s (21:38:09-21:59:19 IST), single process, no other traffic. **98 checks passed, 1 failed.**

The failed check is `zero 5xx` in scenario 6 (sell-out, 6,000 calls): one response had status **520**. Everything else in that scenario held: exactly 200 `201`s for 200 seats, `confirmed 200 == 200`, `available 0`, no seat in two reservations, and `number of 201s (200) == seats confirmed (200)`, so the lost request neither booked a seat nor lost one.

| Outcome | Count |
|---|---|
| `201 created` | 1724 |
| `409 seat_taken` | 34132 |
| `409 per_user_limit` | 1449 |
| `200 replay/ok` | 487 |
| `409 idempotency_conflict` | 1 |
| `520` | 1 |
| `400 invalid_seat` | 60 |

Latency: p50 3908 ms, p95 14197 ms, p99 21094 ms. The 22,000-call stampede ran at 35 requests/s (p50 4.6 s, p99 21.6 s, max 48.4 s); the invariant held on all 196 samples taken during it, `/health/ready` returned `200` on all 196 probes, and the hall sold out exactly (500 confirmed of 500).

| # | Scenario | Seconds | Calls | Result |
|---|---|---|---|---|
| 1 | hot-seat storm | 40.3 | 500 | PASS |
| 2 | on-sale stampede | 662.4 | 22000 | PASS |
| 3 | idempotency | 14.9 | 531 | PASS |
| 4 | per-user limit under concurrency | 46.3 | 1220 | PASS |
| 5 | multi-seat deadlock storm | 11.4 | 300 | PASS |
| 6 | sell-out | 176.0 | 6000 | **FAIL** |
| 7 | seat recycling | 35.6 | 1200 | PASS |
| 8 | chaos mix | 186.8 | 5608 | PASS |
| 9 | confirm vs cancel race on the same hold (30 rounds) | 10.7 | 30 | PASS |
| 10 | hold expiry (needs a short TTL) | 0.0 | 0 | PASS |
| 11 | hostile input and bad credentials under load | 14.4 | 60 | PASS |
| 12 | cross-show isolation | 13.0 | 400 | PASS |
| 13 | ownership, spoofing and cancel safety | 1.3 | 4 | PASS |

Metric reconciliation (asserted by the harness, all matched): confirmed 1696 (1647 reserve `201` + 49 hold confirmations), held 77, `seat_taken` 34132, `per_user_limit` 1449, `idempotent_replay` 487, `idempotency_conflict` 1, `invalid_seat` 60, `hold_expired` 22, `contention` 0. The Prometheus snapshot taken afterwards ([file](evidence/metrics-after-s100.prom)) has no `status="5xx"` series, zero Hikari connection timeouts and zero pending or active connections.

What the `520` is and is not:

* `520` is not a status the application emits (it returns `500` or `503` for its own failures, and its server-side 5xx series is empty). It is an edge-proxy status for an origin that closed the connection or returned an empty or invalid response.
* The server's counters match the client's observations exactly, so the application did not complete that request as a counted outcome. Like the scale-0.5 transport failure, it left no trace behind.
* Process uptime (1,478 s at 16:32:53 UTC) shows the service cold-started at the very start of the run (the harness waited about a minute for readiness) and ran continuously through all of it, so a restart does not explain it.
* **Cause not established.** One candidate is a keep-alive race between Render's proxy and Tomcat, which is configured with a 60 s keep-alive timeout (a proxy reusing a connection the origin has just closed gets an empty reply). Another is plain edge flakiness. Neither has been tested. Raising Tomcat's keep-alive timeout and request limit is a cheap experiment; until it is run and the failure stops recurring, this is only a hypothesis.

Across the largest runs (scale 0.5 twice and scale 1.0, about 77,400 calls at the time) two requests were lost this way; the failure has not recurred in the two large runs since (scale-0.5 run 2 and run 3, about 39,600 calls).

A third clean half-scale run (19,788 calls in 665 s) on the current build confirmed the circuit breaker never opens under load: `/health/ready` stayed `200` on all 79 probes sampled during the stampede, and Hikari recorded zero connection timeouts. No request was lost this time either, so the keep-alive hypothesis remains neither confirmed nor refuted - the failure has simply not recurred in two consecutive large runs. Artifacts: [txt](evidence/burst-live-s050-run3-2026-10-04.txt), [json](evidence/burst-live-s050-run3-2026-10-04.json), [metrics](evidence/metrics-after-s050-run3.prom).

## Database outage test, run 1 (before the circuit breaker and health-check change)

Procedure: `outage_probe.py` against the live service for 360 s (one reservation attempt, `/health/live` and `/health/ready` per second); the Render Postgres was suspended about 10 s in and resumed later. Artifacts: [timeline](evidence/outage-probe-live-2026-10-03.txt), [json](evidence/outage-probe-live-2026-10-03.json).

| t | Observation |
|---|---|
| 0-9 s | healthy: reservations `201` in about 0.3 s |
| 10 s | database suspended. `/health/ready` becomes `503`, `/health/live` stays `200`, the first reservation returns **`503 service_unavailable` with `Retry-After: 5`** |
| 26 s | `/health/live` and `/health/ready` both become **`502`** and stay so; this is Render's proxy answering because the instance was taken out of rotation, not the application |
| 239 s | live and ready are `200` again and reservations return `201`, with no manual redeploy |

Result of the probe: 131 reservations returned `201`, 1 returned `503`, 212 returned `502`. The final audit **passed**: all 131 seats that returned `201` are confirmed, no seat is booked twice, and `available + held + confirmed == total` (239 + 0 + 131 == 370). No `201` was returned during the outage.

Findings:

1. **Data safety held.** Nothing was booked incorrectly, and the application degraded to `503` the moment the database disappeared.
2. **The platform health check defeats graceful degradation.** `render.yaml` sets `healthCheckPath: /health/ready`. Render's documented rule is that an instance failing consecutive health checks for 15 s is taken out of rotation, and one failing for 60 s is restarted ([Render health checks](https://render.com/docs/health-checks)). The measurement matches: about 16 s after readiness began failing, Render stopped routing to the instance, so almost all clients saw `502` instead of the application's `503` + `Retry-After`. Process uptime read afterwards implies the process was (re)started at about 14:05:05 UTC, which is consistent with Render replacing the instance; this was inferred from uptime, not confirmed from Render's event log.
3. **Recommended change (not yet applied):** point the platform health check at `/health/live` and keep `/health/ready` for monitoring. A database outage would then surface to clients as a clean `503`. Requests would wait up to Hikari's 60 s connection timeout before failing, so that timeout would need lowering and the burst re-run.
4. The exact moment the database was resumed was not recorded, so time-to-recover after resume is not stated.
5. Before this change was made the application returned a generic `500` for ordinary requests during an outage; it now returns `503` (covered by `ReadinessFailClosedTest`).

## Database outage test, run 2 (new health check path and circuit breaker)

Same probe, same method, on build `9562747` with Render's health check path set to `/health/live`. The probe started at 16:51:04 UTC; the Postgres was suspended at about 16:51:57 UTC (first failed readiness check in the server log: 16:51:59) and resumed at about 16:56:00 UTC (the user's recorded stamp is 16:56:02, typed after the click). Outage length: about 4 minutes. Artifacts: [timeline](evidence/outage-probe-live-run2-2026-10-03.txt), [json](evidence/outage-probe-live-run2-2026-10-03.json), [server log excerpt](evidence/outage-run2-server-log-excerpt.json), [metrics](evidence/metrics-after-outage-run2.prom), [times](evidence/outage-run2-times.txt).

| Observation | Run 1 | Run 2 |
|---|---|---|
| `/health/live` during the outage | 200, then **502** for 213 s | **200 on every probe** |
| Render pulled the instance out of rotation | yes (after about 16 s) | **no** |
| `/health/ready` | 503, then 502 | 503 (200 again at t=296 s) |
| Reservations during the outage | 1 x `503`, 212 x `502` | **109 x `503` + `Retry-After`, no `502`**, no other 5xx, no transport failures |
| Reservations booked during the outage | none | none |
| Audit after recovery | pass | **pass**: 115 seats returned `201`, 115 confirmed, 255 + 0 + 115 == 370 |
| Recovery after the database answered again | not measurable | about 2 s: the breaker closed at 16:56:01.9, first `201` at 16:56:02 |

The health-check change worked: Render no longer hides the application's own behaviour. Counters agree with the client: 109 `503`s seen by the client equal 79 breaker rejections plus 30 connection-timeout rejections on the server; Hikari recorded 33 connection timeouts (30 requests plus 3 background sweeps).

**A defect surfaced: the breaker opened about 66 s late.** The `503`s fell into two groups. 30 requests sent in the first 68 s of the outage each waited 60.2-61.4 s (Hikari's connection timeout) before failing; the 79 requests sent after that failed in 1.3 s or less. The server log shows why:

| UTC | Event |
|---|---|
| 16:51:59.5 | first failed readiness check (outage began) |
| 16:52:59.4 | `hold sweep failed` (the sweeper had been blocked 60 s on the dead connection pool) |
| 16:53:05.4 | `database unreachable for 3 consecutive probes, failing database requests fast` (6 s later, exactly three 2 s probes) |
| 16:56:01.9 | `database reachable again, requests resume` |

Spring's default scheduler has a single thread, shared by the hold sweeper and the health monitor; the sweeper held it for 60 s, so the probe could not run until it gave up. The fix is a scheduler pool of 4 (`spring.task.scheduling.pool.size`), with `SchedulerIsolationTest` as a regression (it fails when forced back to one thread). **This fix is committed but not yet deployed or re-verified live**, so the measured fail-fast time of 66 s is the pre-fix figure; the expected figure after the fix is about 8 s (three probes of up to 2 s plus the interval).

## Database outage test, run 4 (scheduler fix deployed — the verification run)

Same probe again after `35de58c` (scheduler pool 4) was deployed. The Postgres was suspended about a minute in and resumed about 1.9 minutes later. An intervening run 3 saw nothing because the wrong Render resource was suspended; it is not counted. Artifacts: [timeline](evidence/outage-probe-live-run4-2026-10-04.txt), [json](evidence/outage-probe-live-run4-2026-10-04.json), [server log excerpt](evidence/outage-run4-server-log-excerpt.json), [metrics](evidence/metrics-after-outage-run4.prom).

| Observation | Run 2 | Run 4 |
|---|---|---|
| `/health/live` | 200 throughout | **200 throughout** |
| `/health/ready` | 503 from t=53 to t=296 | 503 from t=107 to t=247 |
| Breaker opened after outage start | **66 s** (single-thread scheduler starved) | **8 s** (log: last good probe ~09:21:46, `database unreachable for 3 consecutive probes` at 09:21:54.9) |
| `503` latency while breaker open | 0.3-1.3 s after opening | **0.2-0.4 s** |
| Reservations during the outage | 109 x `503` + `Retry-After` | 62 x `503` + `Retry-After`, **1 x `500`** |
| Other 5xx / transport failures / `502` | none | none / none / none |
| Audit | pass | **pass**: 216 x `201` = 216 confirmed, 154 + 0 + 216 == 370 |
| Recovery | ~2 s after the DB answered | `database reachable again` at 09:24:05.7, first `201` ~1 s after `ready` returned 200 at t=247 |

The scheduler fix verified live: with a separate thread the monitor detected the outage inside 8 s (three 2 s probes), requests failed fast for the whole outage, and the service recovered by itself. One honest imperfection remains: a single request that was already inside its transaction when the database vanished returned `500 internal_error` instead of `503`, because the commit-time failure threw `TransactionSystemException`, which was not in the 503 mapping. That request raced the suspend itself (t=106, a second before readiness first reported DOWN); the audit confirms it booked nothing. The mapping has since been widened so mid-flight transaction failures also answer `503` + `Retry-After`.

## Seat hints: live A/B on Render Postgres (scale 0.5)

Two runs of the same suite on the same build (`0795d6e`), switching only `SEAT_HINTS_MODE` (`off` then `local`). Artifacts: [off](evidence/burst-hints-off-2026-10-04.txt) ([json](evidence/burst-hints-off-2026-10-04.json), [metrics](evidence/metrics-hints-off.prom)) · [local, failed](evidence/burst-hints-local-run1-FAILED-2026-10-04.txt) ([json](evidence/burst-hints-local-run1-FAILED-2026-10-04.json), [metrics](evidence/metrics-hints-local-run1-FAILED.prom)).

| | hints off | hints local (run 1, **FAILED**) |
|---|---|---|
| Calls | 19,836 | 19,807 |
| Total time | 703.6 s | 592.8 s |
| Stampede (11,000 calls) | 331 s, 33 req/s | 262 s, 42 req/s |
| Overall p50 / p95 / p99 | 4.0 / 14.5 / 21.6 s | 2.3 / 14.0 / 21.1 s |
| Result | all checks passed | **1 check failed** (scenario 3) |
| 5xx / transport failures / Hikari timeouts | 0 / 0 / 0 | 0 / 0 / 0 |
| Hint counters | none | 15,599 hits, 5,763 misses, 2,805 learned, 722 dropped as stale, 445 releases |

The cache made this run about 16 % faster (median latency down from 4.0 s to 2.3 s), but **the run failed a correctness check and is not accepted**: in scenario 3, 3 of the 450 parallel duplicate requests got `409 seat_taken` instead of a replay (297 replays instead of 300). No seat was double-booked and the counters reconciled, but a retried request was declined, which breaks the idempotency promise. Cause: the hint was read after the idempotency lookup, so a duplicate could be rejected by the hint its own winner had just stored. Fixed by reading the hint first; a parallel regression test reproduces the bug on the old code. The speed figures above were measured on the buggy build, so they must be re-measured on the fixed one before any claim is made. Other differences are expected from the documented precedence change: `per_user_limit` declines fell from 719 to 408 because requests doomed for two reasons now answer `seat_taken`.

The unmodified baseline runs for this build size were 650-704 s, so run-to-run variation is roughly 5-8 %; a result must clear that to count.

## Log access

The platform offers no public log URL, so the service exposes `GET /ops/logs` (README, *Public log access*). Checked live: a reservation's `X-Request-ID` returned both its access line and its `reservation confirmed` event ([sample](evidence/logs-live-sample.json)), and a 1000-entry dump taken after a burst contained none of the admin secret, any issued token, `Authorization`, `Bearer`, a stack trace, `jdbc:`, `postgres` or `password`; 977 of 1000 entries carried a `request_id` and the rest were background hold-expiry events. The buffer holds the last 2000 events, which under burst load is roughly the last minute.

## Limits of this evidence (stated plainly)

* **Full-size is now verified:** scale 1.0 run 2 passed cleanly (see above). The earlier `520` did not recur.
* **Hold expiry live.** It was exercised once, at scale 0.2 with an 8 s TTL, where all 23 timed confirms arrived after expiry (0 won). That verifies that expired holds stay expired and nothing is resurrected, but not a confirm winning the race on the live service; that case is covered by the local short-TTL runs and `ConcurrencyStressTest`. The scale-0.5 runs used the production TTL of 300 s, so scenario 10 was skipped.
* **Two unexplained lost requests**: one transport failure in the first scale-0.5 run and one `520` in the full-size run, both described above.
* **Outage test:** three counted runs. Run 4 verified the corrected build end to end. Render's event log was not captured; suspend/resume times are inferred from server log lines and the user's stamps.
* Render's edge returned its own `403` for SQL-injection-looking payloads before they reached the service in the earlier runs; the burst accepts `400` or `403` for that single case.
* Render's free PostgreSQL expires 30 days after creation.
* Docker was not available on the author's machine; Render builds the image on every deploy.

## How to reproduce

```bash
./burst.sh https://seat-reservation-9zhl.onrender.com --admin-secret '<ADMIN_SECRET>' --scale 0.5 --report run.json | tee run.txt
./outage_probe.py https://seat-reservation-9zhl.onrender.com --admin-secret '<ADMIN_SECRET>' --duration 360   # then suspend and resume the database
```
The admin secret is a deployment secret and is not in this repository.
