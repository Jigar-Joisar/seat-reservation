# Live test report

Evidence that the service behaves correctly when deployed, not only on a developer machine.

| | |
|---|---|
| Target | https://seat-reservation-9zhl.onrender.com |
| Platform | Render, Docker web service, **free** instance, single instance |
| Database | Render managed PostgreSQL (free), pool of 16 connections |
| Deployed build | commit `6d1d9b7` (PostgreSQL support); later commits only touch the burst tool, logging endpoint and docs |
| Burst run | `ab238c0e`, started 2026-10-03T18:06:11.891918+05:30, scale 0.2 |
| Hold TTL during run | 8 s (default is 300 s), to exercise expiry |
| Raw artifacts | [`burst-live-2026-10-03.txt`](evidence/burst-live-2026-10-03.txt), [`burst-live-2026-10-03.json`](evidence/burst-live-2026-10-03.json), [`metrics-live-after-burst.prom`](evidence/metrics-live-after-burst.prom) |

## Result

**All 13 scenarios passed: 0 failed checks, 0 responses with 5xx status, 0 transport failures.**
The run was a single process (no overlapping bursts) of 8,922 reserve/hold calls in 374 s.

### Outcome distribution (reserve and hold calls)

| Outcome | Count |
|---|---|
| `201 created` | 1099 |
| `409 seat_taken` | 7107 |
| `200 replay/ok` | 429 |
| `409 per_user_limit` | 274 |
| `409 idempotency_conflict` | 1 |
| `400 invalid_seat` | 12 |

Every decline is a 4xx domain outcome; none is a 5xx. Client-observed counts equal the server's Prometheus counters exactly (next section).

### Latency (client side, per reserve/hold call)

| p50 | p95 | p99 |
|---|---|---|
| 3702 ms | 12806 ms | 18996 ms |

The free instance has a fraction of a CPU, so throughput is about 33 requests/s (about 1,800/s on a laptop). Requests queue instead of failing: correctness is unaffected, latency is. See *Limits of this evidence*.

### Per-scenario timing

| # | Scenario | Seconds | Calls | Result |
|---|---|---|---|---|
| 1 | hot-seat storm | 36.3 | 500 | PASS |
| 2 | on-sale stampede | 145.9 | 4400 | PASS |
| 3 | idempotency | 14.3 | 531 | PASS |
| 4 | per-user limit under concurrency | 9.4 | 260 | PASS |
| 5 | multi-seat deadlock storm | 2.5 | 60 | PASS |
| 6 | sell-out | 40.0 | 1200 | PASS |
| 7 | seat recycling | 7.9 | 240 | PASS |
| 8 | chaos mix | 50.4 | 1259 | PASS |
| 9 | confirm vs cancel race on the same hold (30 rounds) | 11.6 | 30 | PASS |
| 10 | hold expiry (needs a short TTL) | 36.5 | 25 | PASS |
| 11 | hostile input and bad credentials under load | 3.3 | 12 | PASS |
| 12 | cross-show isolation | 12.5 | 400 | PASS |
| 13 | ownership, spoofing and cancel safety | 1.4 | 4 | PASS |

## Metric reconciliation

Prometheus counters read from `/actuator/prometheus` (taken right after the run, before the next deploy reset them) against what the client observed. The service had no other traffic during the run.

| Metric | Server counter | Client observed | Match |
|---|---|---|---|
| `reservations_confirmed_total` | 1049 | 1031 reserve `201` + 18 hold confirmations | yes |
| `reservations_held_total` | 69 | 68 hold `201` in the run + 1 probe hold | yes |
| `reservations_declined_total{reason="seat_taken"}` | 7107 | 7107 | yes |
| `reservations_declined_total{reason="per_user_limit"}` | 274 | 274 | yes |
| `reservations_declined_total{reason="idempotent_replay"}` | 429 | 429 | yes |
| `reservations_declined_total{reason="idempotency_conflict"}` | 1 | 1 | yes |
| `reservations_declined_total{reason="invalid_seat"}` | 12 | 12 | yes |
| `reservations_declined_total{reason="hold_expired"}` | 40 | 40 | yes |
| `reservations_declined_total{reason="contention"}` | 0 | 0 | yes |
| `http_server_requests_seconds_count{status=~"5.."}` | no series | 0 | yes |
| `hikaricp_connections_timeout_total` | 0 | n/a | yes |

The `seats_*` gauges for every show satisfy `available + held + confirmed == total` (the burst asserts this on every poll and in a final audit; the fleet-wide sums are in the `.prom` file).

## Log access

The platform offers no public log URL, so the service exposes `GET /ops/logs` (see the README, *Public log access*). Checked on the live deployment after the burst: a reservation's `X-Request-ID` response header returned both its access line and its `reservation confirmed` event ([sample](evidence/logs-live-sample.json)), and the full 1000-entry dump contained none of: the admin secret, any issued token, `Authorization`, `Bearer`, a stack trace, `jdbc:`, `postgres` or `password`.

## What the scenarios prove

* **No double-sell**: exactly one `201` for a seat contended by 500 users, and a full audit (every reservation fetched and cross-checked against the seat map) found no seat in two live reservations.
* **Per-user limit**: parallel requests never push a user above the limit.
* **Idempotency**: duplicate storms create one reservation per key; same key with different seats is a `409`.
* **Deadlock freedom**: crossed multi-seat orders from hundreds of users produce only `201`/`409`, no lock errors.
* **Holds**: confirm-vs-cancel races have one coherent winner; around the expiry instant a confirm that returned `200` is never undone and every other hold expires.
* **Hostile input**: forged and tampered tokens, malformed JSON, wrong types, injection-looking strings, fractional and huge prices: the expected 4xx every time, no 5xx, show unchanged.
* **Isolation and ownership**: same users and seat names in two shows are independent; non-owners get `403`; a spoofed `user_id` is ignored; a stale cancel never frees a re-booked seat.

## Limits of this evidence (stated plainly)

* The live run used `--scale 0.2` (about 9,000 calls). The default scale (about 22,000 calls) passes locally at about 1,800 requests/s but was **not** run against the free instance, where at about 33 requests/s it would take roughly 11 minutes.
* Hold expiry on the live instance ran with an 8 s TTL while latency was about 4 s, so all 23 timed confirms arrived after expiry (0 won, 23 lost). That verifies "expired holds stay expired and nothing is resurrected" but not a confirm winning the race live. That case is covered by the local runs (short-TTL burst, `ConcurrencyStressTest`).
* Render's edge returned its own `403` page for SQL-injection-looking payloads (12 requests) before they reached the service; the burst accepts `400` or `403` for that single case. The application itself returns `400 invalid_seat` for such input.
* Render's free PostgreSQL expires 30 days after creation. A real network partition between app and database mid-burst was not tested; the readiness check failing closed is covered by `ReadinessFailClosedTest`.
* Docker was not available on the author's machine; the Dockerfile is exercised by Render's own build on every deploy.

## Earlier live runs

| When | Database | Scale | Outcome |
|---|---|---|---|
| 2026-10-03 | embedded H2 (ephemeral) | 0.2 | all correctness checks passed; one test expectation was wrong (Render edge `403`), fixed in `burst.py` |
| 2026-10-03 | PostgreSQL | 0.2 | all checks passed, 8,891 calls, p50 4.1 s, p99 22.0 s, 0 5xx |
| 2026-10-03 | PostgreSQL, 8 s hold TTL | 0.2 | **this report** |
| earlier | H2, overlapping runs (two bursts at once) | 0.2 | 502s from the overloaded free instance; invalid test, discarded; the harness now runs one process |

## How to reproduce

```bash
./burst.sh https://seat-reservation-9zhl.onrender.com --admin-secret '<ADMIN_SECRET>' --scale 0.2 --report run.json | tee run.txt
```
The admin secret is a deployment secret and is not in this repository.
