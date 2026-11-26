package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RULES_V1 =
            "{\"schema_version\":\"1.0\",\"rules_version\":99,\"rules\":[{"
                    + "\"id\":\"r\",\"name\":\"R\","
                    + "\"selector\":{\"kind\":\"api\",\"keys\":{\"method\":\"POST\",\"path\":\"/p\"}},"
                    + "\"enabled\":true,\"priority\":100,"
                    + "\"condition\":{\"all\":[{\"field\":\"body.amount\",\"operator\":\"gt\",\"value\":0}]},"
                    + "\"action\":{\"type\":\"block\"}}]}";

    @TempDir
    Path tmp;

    // ─── defaultCachePath ────────────────────────────────────────────────

    @Test
    void defaultCachePath_uses_java_io_tmpdir() {
        String path = RulesCache.defaultCachePath("prod");
        assertTrue(path.endsWith("ks-cache-prod.json"), path);
        assertTrue(path.startsWith(System.getProperty("java.io.tmpdir")), path);
    }

    @Test
    void defaultCachePath_sanitises_unsafe_chars() {
        String path = RulesCache.defaultCachePath("prod/west");
        assertTrue(path.endsWith("ks-cache-prod_west.json"), path);
    }

    @Test
    void defaultCachePath_empty_env_fallback() {
        String path = RulesCache.defaultCachePath("");
        assertTrue(path.endsWith("ks-cache-default.json"), path);
    }

    // ─── Round trip ──────────────────────────────────────────────────────

    @Test
    void writeThenReadRecoversPayload() throws Exception {
        String path = tmp.resolve("cache.json").toString();
        RulesCache.write(path, RULES_V1, LoggerAdapter.NOOP);
        String got = RulesCache.read(path, LoggerAdapter.NOOP);
        assertNotNull(got);
        JsonNode parsed = MAPPER.readTree(got);
        assertEquals(99, parsed.get("rules_version").asInt());
    }

    @Test
    void writeIsAtomic_noTmpFileLingers() {
        Path path = tmp.resolve("cache.json");
        RulesCache.write(path.toString(), RULES_V1, LoggerAdapter.NOOP);
        assertTrue(Files.notExists(Path.of(path + ".tmp")),
                "tmpfile should be moved away");
    }

    @Test
    void writeCreatesParentDirectory() throws Exception {
        Path path = tmp.resolve("nested/subdir/cache.json");
        RulesCache.write(path.toString(), RULES_V1, LoggerAdapter.NOOP);
        assertTrue(Files.exists(path));
    }

    @Test
    void readReturnsNullForMissingFile() {
        assertNull(RulesCache.read(tmp.resolve("missing.json").toString(), LoggerAdapter.NOOP));
    }

    @Test
    void readReturnsNullForMalformedJson() throws Exception {
        Path path = tmp.resolve("bad.json");
        Files.writeString(path, "{ not valid json");
        assertNull(RulesCache.read(path.toString(), LoggerAdapter.NOOP));
    }

    @Test
    void readReturnsNullForValidJsonButWrongShape() throws Exception {
        Path path = tmp.resolve("wrong-shape.json");
        Files.writeString(path, "{\"unrelated\":\"thing\"}");
        assertNull(RulesCache.read(path.toString(), LoggerAdapter.NOOP));
    }

    @Test
    void readReturnsNullForEmptyFile() throws Exception {
        Path path = tmp.resolve("empty.json");
        Files.writeString(path, "");
        assertNull(RulesCache.read(path.toString(), LoggerAdapter.NOOP));
    }

    @Test
    void readReturnsNullForNullPath() {
        assertNull(RulesCache.read(null, LoggerAdapter.NOOP));
    }

    // ─── Client integration ──────────────────────────────────────────────

    @Test
    void clientInit_writesCacheAfterSuccessfulFetch() throws Exception {
        Path path = tmp.resolve("cache.json");
        RulesFetcher fetcher = () -> RULES_V1;
        try (Client ks = Client.init(Config.builder()
                .fetcher(fetcher)
                .cachePath(path.toString())
                .refreshIntervalMs(0)
                .build())) {
            assertTrue(Files.exists(path), "cache should exist after init");
            JsonNode parsed = MAPPER.readTree(Files.readString(path));
            assertEquals(99, parsed.get("rules_version").asInt());
        }
    }

    @Test
    void clientInit_fallsBackToCache_whenFetchFails() throws Exception {
        Path path = tmp.resolve("cache.json");
        Files.writeString(path, RULES_V1);

        RulesFetcher fetcher = () -> { throw new RuntimeException("backend down"); };
        try (Client ks = Client.init(Config.builder()
                .fetcher(fetcher)
                .cachePath(path.toString())
                .refreshIntervalMs(0)
                .build())) {
            assertEquals(Long.valueOf(99), ks.getStatus().activeRulesVersion());
        }
    }

    @Test
    void clientInit_throws_whenNoCacheAndFetchFails() {
        Path path = tmp.resolve("absent.json");
        RulesFetcher fetcher = () -> { throw new RuntimeException("backend down"); };
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Client.init(Config.builder()
                        .fetcher(fetcher)
                        .cachePath(path.toString())
                        .refreshIntervalMs(0)
                        .build()));
        assertTrue(ex.getMessage().toLowerCase().contains("no rules available"), ex.getMessage());
    }

    @Test
    void clientInit_cachePathUnset_neverReadsDisk_throwsOnFetchFail() {
        RulesFetcher fetcher = () -> { throw new RuntimeException("backend down"); };
        assertThrows(IllegalStateException.class, () ->
                Client.init(Config.builder()
                        .fetcher(fetcher)
                        .refreshIntervalMs(0)
                        .build()));
    }

    @Test
    void clientInit_corruptCacheIsIgnored() throws Exception {
        Path path = tmp.resolve("corrupt.json");
        Files.writeString(path, "{not parseable");

        RulesFetcher fetcher = () -> { throw new RuntimeException("backend down"); };
        assertThrows(IllegalStateException.class, () ->
                Client.init(Config.builder()
                        .fetcher(fetcher)
                        .cachePath(path.toString())
                        .refreshIntervalMs(0)
                        .build()));
    }

    @Test
    void clientInit_successfulFreshFetchOverwritesStaleCache() throws Exception {
        Path path = tmp.resolve("cache.json");
        Files.writeString(path,
                "{\"schema_version\":\"1.0\",\"rules_version\":1,\"rules\":[]}");

        RulesFetcher fetcher = () -> RULES_V1;  // returns version 99
        try (Client ks = Client.init(Config.builder()
                .fetcher(fetcher)
                .cachePath(path.toString())
                .refreshIntervalMs(0)
                .build())) {
            assertEquals(Long.valueOf(99), ks.getStatus().activeRulesVersion());
            // Cache file now reflects the fresh fetch, not the stale value.
            JsonNode parsed = MAPPER.readTree(Files.readString(path));
            assertEquals(99, parsed.get("rules_version").asInt());
        }
    }
}
