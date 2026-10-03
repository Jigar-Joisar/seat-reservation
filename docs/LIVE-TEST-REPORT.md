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

* **Correctness held in every run.** Across the five runs in the table below (about 56,000 reserve/hold calls) no seat was ever sold twice, no user exceeded the limit, no idempotent retry booked twice, and every client-observed outcome equalled the server's Prometheus counters. **No load run produced a 5xx from the application.** The only 5xx-class responses seen were the deliberate `503` during the database outage test and Render's own `502`s during that outage.
* **The scale-0.5 suite passed cleanly on its second run** (19,779 calls, 650 s, 98 checks, 0 failed). Its first run had **one unexplained transport failure** (a request that got no response and timed out client-side), reported below as a failed run.
* **A database outage is survived without data damage, but not gracefully on Render.** The application fails closed (readiness `503`, reservations `503` with `Retry-After`, nothing booked during the outage), yet Render's health check on `/health/ready` pulled the whole instance out of rotation, so clients saw `502` for about 3.5 minutes. See *Database outage test*.
* **Throughput of the free instance is about 25-35 requests/s.** The full-size suite (scale 1.0, about 44,000 calls) was **not run**; the projected duration is about 35 minutes.

## Runs

| Started | Scale | Hold TTL | Calls | Duration | Latency | Result | Notes |
|---|---|---|---|---|---|---|---|
| 2026-10-03 18:06 IST | 0.2 | 8 s | 8,922 | 374 s | p50 3.7 / p99 19.0 s | PASS | all 13 scenarios; hold expiry exercised (0 of 23 timed confirms won the race at ~4 s latency) |
| 2026-10-03 19:38 IST | 0.15 | 300 s | 7,045 | 279 s | p50 3.9 / p99 19.0 s | PASS | scenario 10 skipped (long TTL) |
| 2026-10-03 20:16 IST | 0.5 | 300 s | 19,767 | 844 s | p50 3.9 / p99 20.0 s | **FAIL** | one transport failure in scenario 7, see below |
| 2026-10-03 20:57 IST | 0.5, scenario 7 only | 300 s | 601 | 95 s | p50 4.2 / p99 16.7 s | PASS | rerun of the failed scenario in isolation (terminal output only) |
| 2026-10-03 21:01 IST | 0.5 | 300 s | 19,779 | 650 s | p50 3.6 / p99 20.8 s | PASS | **the reference run**; details below |

Artifacts: [scale 0.2, 8 s TTL](evidence/burst-live-2026-10-03.txt) ([json](evidence/burst-live-2026-10-03.json), [metrics](evidence/metrics-live-after-burst.prom)) · [scale 0.15](evidence/burst-live-s015-2026-10-03.txt) · [scale 0.5 run 1, failed](evidence/burst-live-s050-run1-FAILED-2026-10-03.txt) ([metrics](evidence/metrics-after-s050-run1.prom)) · [scale 0.5 run 2](evidence/burst-live-s050-run2-2026-10-03.txt) ([json](evidence/burst-live-s050-run2-2026-10-03.json), [metrics](evidence/metrics-after-s050-run2.prom)).

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

## Database outage test

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
2. **The platform health check defeats graceful degradation.** `render.yaml` sets `healthCheckPath: /health/ready`. About 16 s after readiness began failing, Render stopped routing to the instance, so almost all clients saw `502` instead of the application's `503` + `Retry-After`. Process uptime read afterwards implies the process was (re)started at about 14:05:05 UTC, which is consistent with Render replacing the instance; this was inferred from uptime, not confirmed from Render's event log.
3. **Recommended change (not yet applied):** point the platform health check at `/health/live` and keep `/health/ready` for monitoring. A database outage would then surface to clients as a clean `503`. Requests would wait up to Hikari's 60 s connection timeout before failing, so that timeout would need lowering and the burst re-run.
4. The exact moment the database was resumed was not recorded, so time-to-recover after resume is not stated.
5. Before this change was made the application returned a generic `500` for ordinary requests during an outage; it now returns `503` (covered by `ReadinessFailClosedTest`).

## Log access

The platform offers no public log URL, so the service exposes `GET /ops/logs` (README, *Public log access*). Checked live: a reservation's `X-Request-ID` returned both its access line and its `reservation confirmed` event ([sample](evidence/logs-live-sample.json)), and a 1000-entry dump taken after a burst contained none of the admin secret, any issued token, `Authorization`, `Bearer`, a stack trace, `jdbc:`, `postgres` or `password`; 977 of 1000 entries carried a `request_id` and the rest were background hold-expiry events. The buffer holds the last 2000 events, which under burst load is roughly the last minute.

## Limits of this evidence (stated plainly)

* **Full size not run.** The default scale (1.0, about 44,000 calls) was not run against the free instance; projected at about 35 minutes. Scale 0.5 is the largest live run.
* **Hold expiry live.** It was exercised once, at scale 0.2 with an 8 s TTL, where all 23 timed confirms arrived after expiry (0 won). That verifies that expired holds stay expired and nothing is resurrected, but not a confirm winning the race on the live service; that case is covered by the local short-TTL runs and `ConcurrencyStressTest`. The scale-0.5 runs used the production TTL of 300 s, so scenario 10 was skipped.
* **One unexplained transport failure** in the first scale-0.5 run, described above.
* **Outage test:** one run; the resume time was not recorded; Render's event log was not captured.
* Render's edge returned its own `403` for SQL-injection-looking payloads before they reached the service in the earlier runs; the burst accepts `400` or `403` for that single case.
* Render's free PostgreSQL expires 30 days after creation.
* Docker was not available on the author's machine; Render builds the image on every deploy.

## How to reproduce

```bash
./burst.sh https://seat-reservation-9zhl.onrender.com --admin-secret '<ADMIN_SECRET>' --scale 0.5 --report run.json | tee run.txt
./outage_probe.py https://seat-reservation-9zhl.onrender.com --admin-secret '<ADMIN_SECRET>' --duration 360   # then suspend and resume the database
```
The admin secret is a deployment secret and is not in this repository.
