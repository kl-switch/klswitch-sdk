package com.klswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Boot-time disk cache for the last-known-good ruleset.
 *
 * <p>Operator opts in via {@code Config.cachePath}. After every
 * successful remote fetch the wrapper persists the accepted ruleset
 * JSON to that path (atomic: tmpfile + {@code ATOMIC_MOVE}). When the
 * SDK boots, if the very first fetch fails AND a cache file exists,
 * the wrapper loads the cache and serves traffic against the
 * last-known-good ruleset while polling keeps retrying the real
 * backend in the background.
 *
 * <p>Pure file IO. The fall-back / persist decisions live in
 * {@link Client}; this class just handles read/write.
 */
public final class RulesCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RulesCache() {}

    /** Conventional path for {@code ${tmpdir}/ks-cache-<env>.json}.
     *  Operators can supply their own path verbatim — this is just the
     *  helper for the common case. Unsafe characters are stripped so
     *  the result is always a sibling of {@code java.io.tmpdir}. */
    public static String defaultCachePath(String env) {
        String safe = (env == null || env.isBlank()) ? "default"
                : env.replaceAll("[^a-zA-Z0-9._-]", "_");
        return Paths.get(System.getProperty("java.io.tmpdir"), "ks-cache-" + safe + ".json").toString();
    }

    /**
     * Read the cached ruleset JSON from {@code path}. Returns {@code null}
     * if the file doesn't exist, is unreadable, empty, or doesn't parse
     * as a ruleset-shaped JSON document. Never throws — a corrupt cache
     * is just "no cache available".
     */
    public static String read(String path, Logger logger) {
        if (path == null) return null;
        Path p = Paths.get(path);
        if (!Files.exists(p)) return null;
        String raw;
        try {
            raw = Files.readString(p);
        } catch (IOException e) {
            logger.warn("ruleset cache unreadable; ignoring", Map.of(
                    "path", path, "error", String.valueOf(e)));
            return null;
        }
        if (raw.isEmpty()) {
            logger.warn("ruleset cache is empty; ignoring", Map.of("path", path));
            return null;
        }
        // Minimal shape check — engine will validate fully on loadRules.
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (!root.has("schema_version") || !root.path("rules").isArray()) {
                logger.warn("ruleset cache shape invalid; ignoring", Map.of("path", path));
                return null;
            }
        } catch (Exception e) {
            logger.warn("ruleset cache JSON parse failed; ignoring", Map.of(
                    "path", path, "error", String.valueOf(e)));
            return null;
        }
        return raw;
    }

    /**
     * Atomically persist {@code rulesJson} to {@code path}. Writes to a
     * sibling {@code <path>.tmp} file then atomically moves it into
     * place — a crash mid-write leaves either the previous good cache
     * OR the new one, never a truncated file.
     *
     * <p>Best-effort: any IO error is logged and swallowed. Disk cache
     * is boot-time resilience, not a critical-path dependency.
     */
    public static void write(String path, String rulesJson, Logger logger) {
        if (path == null) return;
        Path p = Paths.get(path);
        Path tmp = Paths.get(path + ".tmp");
        try {
            Path parent = p.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(tmp, rulesJson);
            try {
                Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                // Some filesystems (e.g. Windows) reject ATOMIC_MOVE — fall
                // back to a non-atomic move so we still get the new cache.
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("ruleset cache write failed", Map.of(
                    "path", path, "error", String.valueOf(e)));
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) { /* best effort */ }
        }
    }
}
