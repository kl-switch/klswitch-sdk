package com.klswitch;

/**
 * Snapshot of the manifest reporter's health, returned by
 * {@code client.manifestStatus()}. When no manifest source was
 * configured, {@link #disabled()} returns the "off" snapshot.
 */
public record ManifestStatus(
        boolean enabled,
        String  framework,
        String  lastReportedAt,
        String  lastHash,
        Boolean lastPushOk,
        String  lastPushError,
        int     routeCount,
        long    pushAttempts,
        long    pushesSucceeded,
        long    pushesFailed) {

    public static ManifestStatus disabled() {
        return new ManifestStatus(
                false, null, null, null, null, null, 0, 0L, 0L, 0L);
    }
}
