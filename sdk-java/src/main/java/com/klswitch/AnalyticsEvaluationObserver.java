package com.klswitch;

import java.time.Instant;
import java.util.List;

/**
 * Builds a decision event and hands it to the reporter.
 *
 * <p>Owns the rule about <em>which</em> evaluations are reportable: only a
 * successful one against a known ruleset version. An {@code ok:false} result is
 * a caller or lifecycle error (INVALID_INPUT / INVALID_HANDLE — the WASM
 * boundary returns these rather than throwing), not a decision, and reporting it
 * would put non-decisions in the decision stream. That judgement is telemetry's,
 * which is why it lives here and no longer in {@code evaluate()}.
 */
final class AnalyticsEvaluationObserver implements EvaluationObserver {

    private final Collaborators.AnalyticsRecorder reporter;

    AnalyticsEvaluationObserver(Collaborators.AnalyticsRecorder reporter) {
        this.reporter = reporter;
    }

    @Override
    public void onEvaluated(Context ctx) {
        if (ctx.rulesVersion() == null || !ctx.result().ok()) {
            return;
        }
        List<AnalyticsEvent.MatchedAction> matched = ctx.result().matched().stream()
                .map(m -> new AnalyticsEvent.MatchedAction(m.id(), m.action().type()))
                .toList();
        // Schema-drift Layer 3: walk each matched rule's condition tree against
        // the input. Computed here, inline, so a slow walk is paid by this caller
        // rather than the analytics flush thread.
        List<String> missingFields = SchemaDrift.computeMissingFields(
                ctx.result().matched(), ctx.conditionsById(), ctx.inputJson());
        reporter.record(new AnalyticsEvent(
                AnalyticsEvent.TYPE,
                AnalyticsEvent.newEventId(),
                Instant.now().toString(),
                ctx.rulesVersion(),
                Client.extractRequest(ctx.inputJson()),
                matched,
                ctx.result().evaluated(),
                ctx.result().skipped(),
                AnalyticsReporter.latencyBucket(ctx.elapsedMs()),
                missingFields));
    }
}
