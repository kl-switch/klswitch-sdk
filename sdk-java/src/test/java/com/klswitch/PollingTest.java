package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PollingTest {

    private static String ruleset(int version) {
        return "{\"schema_version\":\"1.0\",\"rules_version\":" + version + ",\"rules\":[{"
                + "\"id\":\"block\",\"name\":\"Block\","
                + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                + "\"enabled\":true,\"priority\":100,"
                + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":100}]},"
                + "\"action\":{\"type\":\"block\"}}]}";
    }

    private static final String INPUT =
            "{\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"facts\":{\"headers\":{},\"query\":{},\"params\":{},\"body\":{\"amount\":500},\"meta\":{}}}";

    @Test
    void initFromFetcherActivatesRules() {
        RulesFetcher fetcher = () -> ruleset(5);
        try (Client ks = Client.init(Config.builder().fetcher(fetcher).build())) {
            Status status = ks.getStatus();
            assertEquals(Long.valueOf(5), status.activeRulesVersion());
            assertEquals(Boolean.TRUE, status.lastRefreshOk());
            assertEquals(1, ks.evaluate(INPUT).matched().size());
        }
    }

    @Test
    void refreshSwapsInNewVersion() {
        int[] version = {5};
        RulesFetcher fetcher = () -> ruleset(version[0]);
        try (Client ks = Client.init(Config.builder().fetcher(fetcher).build())) {
            assertEquals(Long.valueOf(5), ks.getStatus().activeRulesVersion());
            version[0] = 9;
            ks.refresh();
            assertEquals(Long.valueOf(9), ks.getStatus().activeRulesVersion());
            assertEquals(Boolean.TRUE, ks.getStatus().lastRefreshOk());
        }
    }

    @Test
    void refreshFailureKeepsLastKnownGood() {
        boolean[] fail = {false};
        RulesFetcher fetcher = () -> {
            if (fail[0]) {
                throw new RuntimeException("network down");
            }
            return ruleset(5);
        };
        try (Client ks = Client.init(Config.builder().fetcher(fetcher).build())) {
            assertEquals(Long.valueOf(5), ks.getStatus().activeRulesVersion());
            fail[0] = true;
            ks.refresh(); // fetch fails; active rules preserved
            assertEquals(Long.valueOf(5), ks.getStatus().activeRulesVersion());
            assertEquals(Boolean.FALSE, ks.getStatus().lastRefreshOk());
            assertEquals(1, ks.evaluate(INPUT).matched().size());
        }
    }

    @Test
    void initFailsWhenFetchFailsAndNoInline() {
        RulesFetcher fetcher = () -> {
            throw new RuntimeException("network down");
        };
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> Client.init(Config.builder().fetcher(fetcher).build()));
        assertTrue(ex.getMessage().toLowerCase().contains("no rules available"));
    }

    @Test
    void negativeRefreshIntervalIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Config.builder().rules(ruleset(1)).refreshIntervalMs(-1).build());
    }

    @Test
    void refreshIntervalZeroDisablesPollingButInlineRulesStillWork() {
        try (Client ks = Client.init(Config.builder().rules(ruleset(1)).refreshIntervalMs(0).build())) {
            assertFalse(ks.getStatus().polling());
            assertEquals(Long.valueOf(1), ks.getStatus().activeRulesVersion());
        }
    }

    @Test
    void recurringPollPicksUpNewRules() throws Exception {
        int[] version = {1};
        RulesFetcher fetcher = () -> ruleset(version[0]);
        try (Client ks = Client.init(Config.builder().fetcher(fetcher).refreshIntervalMs(50).build())) {
            assertEquals(Long.valueOf(1), ks.getStatus().activeRulesVersion());
            version[0] = 2;
            // wait for the daemon poll to pick up v2
            long deadline = System.currentTimeMillis() + 5000;
            while (!Long.valueOf(2).equals(ks.getStatus().activeRulesVersion())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(Long.valueOf(2), ks.getStatus().activeRulesVersion());
            assertTrue(ks.getStatus().polling());
        }
    }

    @Test
    void refreshWithoutSourceThrows() {
        try (Client ks = Client.init(Config.builder().rules(ruleset(1)).build())) {
            assertThrows(UnsupportedOperationException.class, ks::refresh);
            assertFalse(ks.getStatus().polling());
        }
    }
}
