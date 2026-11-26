package com.klswitch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Notified after every evaluation, so that {@code evaluate()} can evaluate.
 *
 * <p>Before this existed, {@code evaluate()} also re-checked the route manifest,
 * timed itself, walked condition trees for missing fields and assembled an
 * analytics event — four reasons for one method to change, three of them
 * telemetry riding on the hot path. They are observers now; the hot path
 * notifies and returns.
 *
 * <p>Implementations must not throw and must not block: they run inline, on the
 * caller's thread, between the engine returning and the caller getting its
 * result.
 */
public interface EvaluationObserver {

    /**
     * Everything an observer could want about one evaluation, gathered once by
     * the caller so no observer has to reach back into the client.
     *
     * <p>{@code conditionsById} is passed by reference — observers must not
     * mutate it.
     */
    record Context(
            String inputJson,
            EvaluationResult result,
            double elapsedMs,
            Long rulesVersion,
            Map<String, JsonNode> conditionsById) {}

    void onEvaluated(Context ctx);

    /** Does nothing. The default, so the hot path never branches on null. */
    EvaluationObserver NOOP = ctx -> { };
}
