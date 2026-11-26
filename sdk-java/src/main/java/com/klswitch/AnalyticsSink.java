package com.klswitch;

import java.util.List;

/** Receives batches of analytics events. Should not throw; errors are logged. */
@FunctionalInterface
public interface AnalyticsSink {
    void accept(List<AnalyticsEvent> batch);
}
