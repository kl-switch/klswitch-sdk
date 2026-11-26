package klswitch

import (
	"context"
	"sync"
	"time"
)

// EvaluationContext is everything an observer could want about one evaluation,
// gathered once by the caller so no observer has to reach back into the Client.
//
// RulesByID is passed by reference — observers must not mutate it.
type EvaluationContext struct {
	Input        EvaluationInput
	Result       EvaluationResult
	Elapsed      time.Duration
	RulesVersion *int
	RulesByID    map[string]Rule
}

// EvaluationObserver is notified after every evaluation, so that Evaluate can
// evaluate.
//
// Before this existed, Evaluate also re-checked the route manifest, timed
// itself, walked condition trees for missing fields and assembled an analytics
// event — four reasons for one method to change, three of them telemetry riding
// on the hot path. They are observers now; the hot path notifies and returns.
//
// Implementations must not panic and must not block: they run inline, on the
// caller's goroutine, between the engine returning and the caller getting its
// result.
type EvaluationObserver interface {
	OnEvaluated(ctx EvaluationContext)
}

// noopObserver does nothing. The default, so the hot path never branches on nil.
type noopObserver struct{}

func (noopObserver) OnEvaluated(EvaluationContext) {}

// analyticsObserver builds a decision event and hands it to the reporter.
//
// It owns the rule about which evaluations are reportable: only a successful one
// against a known ruleset version. An Ok:false result is a caller or lifecycle
// error (INVALID_INPUT / INVALID_HANDLE — the WASM boundary returns these rather
// than erroring), not a decision, and reporting it would put non-decisions in the
// decision stream. That judgement is telemetry's, which is why it lives here and
// no longer in Evaluate.
type analyticsObserver struct {
	reporter AnalyticsRecorder
}

func (o analyticsObserver) OnEvaluated(ctx EvaluationContext) {
	if ctx.RulesVersion == nil || !ctx.Result.Ok {
		return
	}
	matched := make([]MatchedAction, 0, len(ctx.Result.Matched))
	for _, m := range ctx.Result.Matched {
		matched = append(matched, MatchedAction{
			RuleID:     m.ID,
			ActionType: m.Action.Type,
		})
	}
	// Schema-drift Layer 3: walk each matched rule's condition tree against the
	// input. Computed here, inline, so a slow walk is paid by this caller rather
	// than the analytics flush goroutine.
	missingFields := computeMissingFields(ctx.Result.Matched, ctx.RulesByID, ctx.Input)
	o.reporter.Record(AnalyticsEvent{
		Type:          AnalyticsEventTypeEvaluation,
		EventID:       newEventID(),
		OccurredAt:    time.Now().UTC().Format(time.RFC3339Nano),
		RulesVersion:  *ctx.RulesVersion,
		Request:       extractAnalyticsRequest(ctx.Input),
		Matched:       matched,
		Evaluated:     ctx.Result.Evaluated,
		Skipped:       ctx.Result.Skipped,
		LatencyBucket: latencyBucket(ctx.Elapsed),
		MissingFields: missingFields,
	})
}

// manifestRecheckObserver pushes the route manifest once, on the first
// evaluation.
//
// This cannot move to construction, which is where it belongs on the face of it:
// routes are registered after the client exists, and the initial deferred push
// can walk an empty route table. By the first request every route exists. So the
// trigger stays where it was; what changed is that Evaluate no longer knows about
// it. Cheap: Report hashes the route list and skips the network when nothing has
// changed.
type manifestRecheckObserver struct {
	reporter ManifestControl
	once     sync.Once
}

func (o *manifestRecheckObserver) OnEvaluated(EvaluationContext) {
	o.once.Do(func() {
		go o.reporter.Report(context.Background())
	})
}

// compositeObserver fans out to several observers, in order.
type compositeObserver struct {
	observers []EvaluationObserver
}

func (c compositeObserver) OnEvaluated(ctx EvaluationContext) {
	for _, o := range c.observers {
		o.OnEvaluated(ctx)
	}
}
