package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * B2 — Exponential backoff tests. Drives PollingManager via a hand-rolled
 * RulesFetcher that flips between success and failure on demand so the
 * backoff math is observable without spinning up a server.
 */
class PollingBackoffTest {

    private static RulesFetcher alternating(int[] mode) {
        // mode[0] = 0 → success, 1 → failure
        return new RulesFetcher() {
            @Override
            public String fetch() throws Exception {
                if (mode[0] == 1) throw new RuntimeException("boom");
                return ruleset(1);
            }
        };
    }

    private static String ruleset(int v) {
        return "{\"schema_version\":\"1.0\",\"rules_version\":" + v + ",\"rules\":[]}";
    }

    private static PollingManager pm(RulesFetcher fetcher, AtomicInteger fetched) {
        return new PollingManager(
                fetcher,
                LoggerAdapter.NOOP,
                body -> fetched.incrementAndGet(),
                err -> {});
    }

    @Test
    void noFailuresMeansDelayIsCurrentInterval() {
        AtomicInteger fetched = new AtomicInteger();
        PollingManager p = pm(alternating(new int[]{0}), fetched);
        // Seed currentInterval via start() (uses bootstrap=30s until a server
        // response retunes it; we don't run start here — pollOnce alone does
        // not set currentInterval, so nextDelayMs falls back to bootstrap).
        p.pollOnce();
        // Without a Cache-Control retune, currentInterval is 0 → nextDelayMs
        // falls back to bootstrap=30s; here bootstrap is unset → 30_000 default.
        assertEquals(30_000L, p.nextDelayMs());
    }

    @Test
    void failuresGrowDelayPastCurrentInterval() {
        int[] mode = {1};
        AtomicInteger fetched = new AtomicInteger();
        PollingManager p = pm(alternating(mode), fetched);
        // Two failures back-to-back.
        p.pollOnce();
        p.pollOnce();
        long delay = p.nextDelayMs();
        assertTrue(delay > 30_000L,
                "expected backoff > base after 2 failures, got " + delay);
        assertTrue(delay <= PollingManager.MAX_BACKOFF_MS * 5L / 4L,
                "backoff must stay within max + jitter, got " + delay);
    }

    @Test
    void successAfterFailuresResetsBackoff() {
        int[] mode = {1};
        AtomicInteger fetched = new AtomicInteger();
        PollingManager p = pm(alternating(mode), fetched);
        p.pollOnce();
        p.pollOnce();
        p.pollOnce();
        // Recovery.
        mode[0] = 0;
        p.pollOnce();
        // Back to base — no jitter window because zero failures.
        assertEquals(30_000L, p.nextDelayMs());
    }

    @Test
    void maxBackoffMsIsFiveMinutes() {
        assertEquals(5L * 60L * 1000L, PollingManager.MAX_BACKOFF_MS);
    }

    @Test
    void longServerCadenceIsNeverUndercutByCap() {
        // Custom fetcher that surfaces a 10-minute Cache-Control on success.
        int[] mode = {0};
        AtomicInteger fetched = new AtomicInteger();
        RulesFetcher fetcher = new RulesFetcher() {
            @Override
            public String fetch() throws Exception {
                if (mode[0] == 1) throw new RuntimeException("boom");
                return ruleset(1);
            }
            @Override
            public FetchResult fetchWithMeta() throws Exception {
                if (mode[0] == 1) throw new RuntimeException("boom");
                return new FetchResult(
                        Optional.of(ruleset(1)),
                        Optional.of("\"v1\""),
                        OptionalLong.of(600_000L)); // 10 min
            }
        };
        PollingManager p = pm(fetcher, fetched);
        p.pollOnce();             // success — currentInterval = 10min
        mode[0] = 1;
        p.pollOnce();             // 1 failure
        p.pollOnce();             // 2 failures
        long delay = p.nextDelayMs();
        // Even with two failures, the schedule must not undercut the
        // 10-minute success cadence. With ±25 % jitter the lower bound
        // is 0.75 * 10min = 7.5 min.
        assertTrue(delay >= 600_000L * 3L / 4L,
                "long server cadence undercut on failure: " + delay);
    }
}
