package klswitch

import "context"

// What the Client needs from its collaborators — and nothing more.
//
// The Client used to hold *pollingManager, *analyticsReporter and
// *ManifestReporter by their concrete types, which meant none of them could be
// substituted in a test without the real thing: a real HTTP transport, a real
// ticker, a real route walk.
//
// These interfaces are deliberately narrow. A single fat Reporter interface
// would force a fake to implement batching, flushing and single-flight logic
// that the Client never asks for.
//
// Go interfaces are structural, so the concrete types already satisfy these; the
// compile-time assertions at the bottom make a breaking change fail in this file
// rather than at a distant call site.

// AnalyticsRecorder is the recording half of analytics — what the observer needs.
type AnalyticsRecorder interface {
	Record(e AnalyticsEvent)
}

// AnalyticsControl is the lifecycle half — what the Client needs.
//
// Split from AnalyticsRecorder on purpose: the observer records and never
// closes, the Client closes and never records. One interface carrying both would
// hand each of them a method it has no business calling.
type AnalyticsControl interface {
	Status() AnalyticsStatus
	FlushNow()
	Close()
}

// ManifestControl is what the Client needs from manifest reporting.
type ManifestControl interface {
	Report(ctx context.Context) bool
	Status() ManifestStatus
	Stop()
}

// PollingControl is what the Client needs from the polling loop.
type PollingControl interface {
	Start(parentCtx context.Context)
	PollOnce(ctx context.Context)
	Status() PollingStatus
	Close()
}

// Compile-time proof that the real implementations still satisfy the contracts.
var (
	_ AnalyticsRecorder = (*analyticsReporter)(nil)
	_ AnalyticsControl  = (*analyticsReporter)(nil)
	_ ManifestControl   = (*ManifestReporter)(nil)
	_ PollingControl    = (*pollingManager)(nil)
)
