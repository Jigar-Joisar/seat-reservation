package com.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Randomized and adversarial concurrency: every run must end with zero 5xx and a database that passes all cross-checks. */
class ConcurrencyStressTest extends AbstractApiTest {

    private static final Set<Integer> DOMAIN = Set.of(200, 201, 409);

    private static List<String> pick(Random rnd, int universe, int n) {
        List<Integer> all = IntStream.rangeClosed(1, universe).boxed().collect(Collectors.toList());
        Collections.shuffle(all, rnd);
        return all.subList(0, n).stream().map(i -> "A" + i).collect(Collectors.toList());
    }

    @Test void randomOperationMixKeepsEveryInvariant() throws Exception {
        String id = show(30, 3);
        int users = 40, ops = 60;
        ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
        parallel(users, u -> {
            Random rnd = new Random(1000 + u);
            String tok = token("chaos" + u);
            List<String> mine = new ArrayList<>();
            List<Object[]> sent = new ArrayList<>();
            for (int i = 0; i < ops; i++) {
                double d = rnd.nextDouble();
                Resp r;
                if (d < 0.35 || mine.isEmpty() && d >= 0.55 && d < 0.85) {
                    List<String> seats = pick(rnd, 30, 1 + rnd.nextInt(3));
                    String key = "u" + u + "-" + i;
                    sent.add(new Object[]{key, seats});
                    r = reserve(id, tok, key, seats.toArray(new String[0]));
                    if (r.status() == 201) mine.add(r.body().get("reservation_id").asText());
                } else if (d < 0.55) {
                    List<String> seats = pick(rnd, 30, 1 + rnd.nextInt(2));
                    String key = "h" + u + "-" + i;
                    r = hold(id, tok, key, seats.toArray(new String[0]));
                    if (r.status() == 201) mine.add(r.body().get("reservation_id").asText());
                } else if (d < 0.70) {
                    r = call("POST", "/reservations/" + mine.get(rnd.nextInt(mine.size())) + "/confirm", tok, null);
                } else if (d < 0.85) {
                    r = call("POST", "/reservations/" + mine.get(rnd.nextInt(mine.size())) + "/cancel", tok, null);
                } else if (d < 0.93 && !sent.isEmpty()) {
                    Object[] s = sent.get(rnd.nextInt(sent.size()));
                    @SuppressWarnings("unchecked") List<String> seats = (List<String>) s[1];
                    r = reserve(id, tok, (String) s[0], seats.toArray(new String[0]));
                } else {
                    r = call("GET", "/shows/" + id, null, null);
                    JsonNode b = r.body();
                    if (b.get("available").asInt() + b.get("held").asInt() + b.get("confirmed").asInt() != b.get("total_seats").asInt()) problems.add("live invariant broken: " + b);
                    continue;
                }
                if (r.status() >= 500 || !DOMAIN.contains(r.status())) problems.add("unexpected " + r.status() + " " + r.raw());
            }
            return null;
        });
        assertTrue(problems.isEmpty(), problems.stream().limit(5).collect(Collectors.joining("\n")));
        assertInvariant(id);
        assertDatabaseConsistent(id);
        Thread.sleep(3500); // TTL=2s, sweeper=300ms: every still-held reservation must now be expired and released
        assertDatabaseConsistent(id);
        assertEquals(0, showState(id).get("held").asInt(), "all holds expired");
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id=? AND status='held'", Long.class, id));
    }

    @Test void reserveAndCancelAccountingIsExact() throws Exception {
        String id = show(40, 4);
        AtomicInteger net = new AtomicInteger();
        AtomicInteger fiveXx = new AtomicInteger();
        parallel(50, u -> {
            Random rnd = new Random(5000 + u);
            String tok = token("acct" + u);
            Deque<Object[]> live = new ArrayDeque<>();
            for (int i = 0; i < 40; i++) {
                Resp r;
                if (rnd.nextDouble() < 0.6 || live.isEmpty()) {
                    r = reserve(id, tok, "a" + u + "-" + i, pick(rnd, 40, 1 + rnd.nextInt(2)).toArray(new String[0]));
                    if (r.status() == 201) { int n = r.body().get("seats").size(); net.addAndGet(n); live.push(new Object[]{r.body().get("reservation_id").asText(), n}); }
                } else {
                    Object[] x = live.pop();
                    r = call("POST", "/reservations/" + x[0] + "/cancel", tok, null);
                    if (r.status() == 200) net.addAndGet(-(Integer) x[1]);
                }
                if (r.status() >= 500) fiveXx.incrementAndGet();
            }
            return null;
        });
        assertEquals(0, fiveXx.get());
        assertEquals(net.get(), showState(id).get("confirmed").asInt(), "seats sold minus seats cancelled == seats confirmed");
        assertDatabaseConsistent(id);
    }

    @Test void crossedMultiSeatOrdersNeverDeadlock() throws Exception {
        String id = show(6, 3);
        List<Resp> rs = parallel(120, i -> {
            Random rnd = new Random(i);
            List<String> seats = pick(rnd, 6, 2 + rnd.nextInt(2));
            return reserve(id, token("dl" + i), "k" + i, seats.toArray(new String[0]));
        });
        assertTrue(rs.stream().allMatch(r -> r.status() == 201 || r.status() == 409), rs.stream().filter(r -> r.status() != 201 && r.status() != 409).map(Resp::raw).findFirst().orElse(""));
        assertTrue(showState(id).get("confirmed").asInt() <= 6);
        long won = rs.stream().filter(r -> r.status() == 201).mapToLong(r -> r.body().get("seats").size()).sum();
        assertEquals(won, showState(id).get("confirmed").asLong());
        assertDatabaseConsistent(id);
    }

    @Test void cancelRacingConfirmEndsCancelledAndConsistent() throws Exception {
        String id = show(30, 1);
        for (int i = 1; i <= 30; i++) {
            String u = token("race" + i);
            Resp h = hold(id, u, "k", "A" + i);
            String rid = h.body().get("reservation_id").asText();
            List<Resp> rs = parallel(2, k -> k == 0 ? call("POST", "/reservations/" + rid + "/confirm", u, null) : call("POST", "/reservations/" + rid + "/cancel", u, null));
            assertEquals(200, rs.get(1).status(), "cancel always succeeds");
            assertTrue(rs.get(0).status() == 200 || rs.get(0).status() == 409, rs.get(0).raw());
            assertEquals("cancelled", call("GET", "/reservations/" + rid, u, null).body().get("status").asText());
        }
        assertEquals(30, showState(id).get("available").asInt());
        assertDatabaseConsistent(id);
    }

    @Test void confirmRacingExpiryNeverLosesAConfirmedSeat() throws Exception {
        String id = show(24, 1);
        // TTL is 2s in tests. Confirm attempts are fired in a window straddling expiry.
        Map<Integer, Integer> confirmStatus = new ConcurrentHashMap<>();
        Map<Integer, String> rids = new ConcurrentHashMap<>();
        Map<Integer, String> toks = new ConcurrentHashMap<>();
        for (int i = 1; i <= 24; i++) {
            toks.put(i, token("exp" + i));
            Resp h = hold(id, toks.get(i), "k", "A" + i);
            assertEquals(201, h.status());
            rids.put(i, h.body().get("reservation_id").asText());
        }
        parallel(24, k -> {
            int i = k + 1;
            try { Thread.sleep(1700 + ThreadLocalRandom.current().nextInt(700)); } catch (InterruptedException e) { throw new RuntimeException(e); }
            Resp r = call("POST", "/reservations/" + rids.get(i) + "/confirm", toks.get(i), null);
            confirmStatus.put(i, r.status());
            return r;
        });
        Thread.sleep(1500);
        int ok = 0;
        for (int i = 1; i <= 24; i++) {
            String status = call("GET", "/reservations/" + rids.get(i), toks.get(i), null).body().get("status").asText();
            int c = confirmStatus.get(i);
            assertTrue(c == 200 || c == 409, "confirm status " + c);
            if (c == 200) { ok++; assertEquals("confirmed", status, "a confirm that returned 200 must never be undone by expiry (seat A" + i + ")"); }
            else assertEquals("expired", status, "a rejected confirm leaves an expired hold");
        }
        assertEquals(ok, showState(id).get("confirmed").asInt());
        assertEquals(24 - ok, showState(id).get("available").asInt());
        assertDatabaseConsistent(id);
    }

    @Test void mixedReserveAndHoldByOneUserRespectsTheLimit() throws Exception {
        String id = show(30, 4);
        String u = token("mixed");
        List<Resp> rs = parallel(12, i -> i % 2 == 0 ? reserve(id, u, "m" + i, "A" + (i + 1)) : hold(id, u, "m" + i, "A" + (i + 1)));
        assertEquals(4, rs.stream().filter(r -> r.status() == 201).count());
        assertEquals(8, rs.stream().filter(r -> r.status() == 409 && "per_user_limit".equals(r.body().get("error").asText())).count());
        assertDatabaseConsistent(id);
    }

    @Test void manyUsersManySeatsNeverOversell() throws Exception {
        String id = show(50, 4);
        List<Resp> rs = parallel(400, i -> {
            Random rnd = new Random(i);
            return reserve(id, token("many" + (i % 100)), "k" + i, pick(rnd, 50, 1 + rnd.nextInt(2)).toArray(new String[0]));
        });
        assertTrue(rs.stream().allMatch(r -> DOMAIN.contains(r.status())));
        long seats = rs.stream().filter(r -> r.status() == 201).mapToLong(r -> r.body().get("seats").size()).sum();
        assertEquals(seats, showState(id).get("confirmed").asLong());
        assertTrue(seats <= 50);
        assertDatabaseConsistent(id);
    }

    @Test void sameKeyFromDifferentUsersCreatesIndependentReservations() throws Exception {
        String id = show(40, 4);
        List<Resp> rs = parallel(30, i -> reserve(id, token("shared" + i), "SAME-KEY", "A" + (i + 1)));
        assertEquals(30, rs.stream().filter(r -> r.status() == 201).count());
        assertEquals(30, rs.stream().map(r -> r.body().get("reservation_id").asText()).distinct().count());
        assertDatabaseConsistent(id);
    }

    @Test void hotSeatRecyclingNeverHasTwoOwners() throws Exception {
        String id = show(3, 4);
        AtomicInteger wins = new AtomicInteger(), cancels = new AtomicInteger();
        parallel(100, u -> {
            String tok = token("recycle" + u);
            for (int round = 0; round < 8; round++) {
                Resp r = reserve(id, tok, "k" + u + "-" + round, "A1");
                assertTrue(r.status() == 201 || r.status() == 409, r.raw());
                if (r.status() == 201) {
                    wins.incrementAndGet();
                    assertEquals(200, call("POST", "/reservations/" + r.body().get("reservation_id").asText() + "/cancel", tok, null).status());
                    cancels.incrementAndGet();
                }
            }
            return null;
        });
        assertEquals(wins.get(), cancels.get());
        assertTrue(wins.get() >= 1);
        assertEquals(3, showState(id).get("available").asInt(), "everything released at the end");
        assertDatabaseConsistent(id);
    }

    @Test void metricsReconcileWithObservedOutcomes() throws Exception {
        String id = show(10, 4);
        Map<String, Double> before = scrape();
        List<Resp> rs = parallel(120, i -> reserve(id, token("met" + i), "k" + i, "A1"));
        List<Resp> replays = parallel(10, i -> reserve(id, token("met0"), "k0", "A1"));
        Map<String, Double> after = scrape();
        long created = rs.stream().filter(r -> r.status() == 201).count();
        long taken = rs.stream().filter(r -> r.status() == 409).count();
        long replayCount = replays.stream().filter(r -> r.status() == 200).count() + (created == 0 ? 0 : 0);
        assertEquals(1, created);
        assertEquals(created, after.get("reservations_confirmed_total") - before.get("reservations_confirmed_total"), 0.001);
        assertEquals(taken, after.get("reservations_declined_total{reason=\"seat_taken\",}") - before.get("reservations_declined_total{reason=\"seat_taken\",}") - countTaken(replays), 0.001);
        assertEquals(replayCount, after.get("reservations_declined_total{reason=\"idempotent_replay\",}") - before.get("reservations_declined_total{reason=\"idempotent_replay\",}"), 0.001);
        assertEquals(9, after.get("seats_available{show_id=\"" + id + "\",}"), 0.001);
        JsonNode s = showState(id);
        assertEquals(s.get("available").asDouble(), after.get("seats_available{show_id=\"" + id + "\",}"), 0.001);
    }

    private long countTaken(List<Resp> rs) { return rs.stream().filter(r -> r.status() == 409 && "seat_taken".equals(r.body().get("error").asText())).count(); }

    private Map<String, Double> scrape() throws Exception {
        Thread.sleep(400); // gauge cache TTL is 250ms
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString()).body();
        Map<String, Double> m = new HashMap<>();
        for (String line : body.split("\n")) {
            if (line.startsWith("#")) continue;
            int sp = line.lastIndexOf(' ');
            if (sp > 0) try { m.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1))); } catch (NumberFormatException ignored) { }
        }
        return m;
    }
}
