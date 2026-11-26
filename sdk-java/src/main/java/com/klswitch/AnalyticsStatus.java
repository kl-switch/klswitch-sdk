package com.klswitch;

/**
 * Snapshot of the analytics reporter's health — surfaced via
 * {@link Client#analyticsStatus()} so the console can render "instance X
 * dropped N events" badges without further coordination.
 */
public record AnalyticsStatus(
        boolean enabled,
        int  bufferSize,
        int  bufferCapacity,
        long eventsRecorded,
        long eventsDropped,
        long eventsSent,
        long batchesFailed,
        String lastFlushAt,        // ISO 8601 or null
        Boolean lastFlushOk,       // null until first flush
        String lastFlushError,     // null on success / not-yet-flushed
        int  consecutiveFailures) {

    public static AnalyticsStatus disabled() {
        return new AnalyticsStatus(
                false, 0, 0, 0L, 0L, 0L, 0L, null, null, null, 0);
    }
}
