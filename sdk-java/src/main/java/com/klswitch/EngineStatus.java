package com.klswitch;

/** Engine status snapshot (matches spec/schemas/engine-status.schema.json). */
public record EngineStatus(
        boolean initialized,
        String schemaVersion,
        Long activeRulesVersion,
        long ruleCount,
        boolean lastLoadOk,
        ErrorInfo lastError) {}
