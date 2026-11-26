package com.klswitch;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Cross-language latency bucket enum — matches Node/Go/Python and the
 * console's {@code LATENCY_BUCKETS}. Sent as the JSON string of the
 * wire-format name (see {@link #toJson}).
 */
public enum LatencyBucket {
    LT_50US("lt_50us"),
    LT_500US("lt_500us"),
    LT_5MS("lt_5ms"),
    GE_5MS("ge_5ms");

    private final String wire;

    LatencyBucket(String wire) { this.wire = wire; }

    /** Jackson uses this — emits the wire-format string instead of the enum name. */
    @JsonValue
    public String toJson() { return wire; }

    /** Map an elapsed-ms duration to the right bucket. */
    public static LatencyBucket forMillis(double ms) {
        if (ms < 0.05) return LT_50US;
        if (ms < 0.5)  return LT_500US;
        if (ms < 5)    return LT_5MS;
        return GE_5MS;
    }
}
