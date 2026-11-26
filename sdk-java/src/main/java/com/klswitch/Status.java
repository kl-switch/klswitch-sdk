package com.klswitch;

/**
 * Combined wrapper status: the engine's status fields (flattened, matching the
 * Node SDK) plus wrapper-owned runtime fields (the host's view of polling/refresh).
 */
public record Status(
        boolean initialized,
        String schemaVersion,
        Long activeRulesVersion,
        long ruleCount,
        boolean lastLoadOk,
        ErrorInfo lastError,
        boolean ready,
        boolean polling,
        Long lastRefreshAt,
        Boolean lastRefreshOk) {

    static Status of(EngineStatus e, boolean ready, boolean polling, Long lastRefreshAt, Boolean lastRefreshOk) {
        return new Status(
                e.initialized(),
                e.schemaVersion(),
                e.activeRulesVersion(),
                e.ruleCount(),
                e.lastLoadOk(),
                e.lastError(),
                ready,
                polling,
                lastRefreshAt,
                lastRefreshOk);
    }
}
