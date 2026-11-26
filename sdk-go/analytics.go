package klswitch

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Decision telemetry.
//
// One event per Client.Evaluate call (including no-match cases). Events live
// in a bounded ring buffer; a single background goroutine flushes batches as
// gzipped POSTs to /v1/decisions.
//
// Overflow policy: drop-oldest. When the buffer fills (backend outage, etc.),
// the reporter keeps the most-recent activity — operators reach for the
// dashboard during incidents, not last week's events.
//
// Failure policy: drop-batch, no retry. A failed POST loses that batch; the
// next flush picks up newer events. Retry without an idempotency key would
// create duplicates if the original landed but the response was lost.
//
// Custom sink: when AnalyticsOptions.Sink is non-nil, the reporter bypasses
// the HTTP POST entirely — useful for piping events into the operator's own
// analytics pipeline. SDK-side ring/flush plumbing still applies.

// LatencyBucket is the cross-language enum the console expects. Matches
// Node, Java, Python wrappers + lib/zod/analytics.ts.
type LatencyBucket string

const (
	LatencyBucketLt50us  LatencyBucket = "lt_50us"
	LatencyBucketLt500us LatencyBucket = "lt_500us"
	LatencyBucketLt5ms   LatencyBucket = "lt_5ms"
	LatencyBucketGe5ms   LatencyBucket = "ge_5ms"
)

// Defaults exposed so callers can reference the same constants the SDK uses.
const (
	DefaultAnalyticsBatchSize     = 100
	DefaultAnalyticsFlushInterval = 5 * time.Second
	DefaultAnalyticsMaxQueueSize  = 10_000
	DefaultAnalyticsPostTimeout   = 5 * time.Second
)

// AnalyticsEvent is the wire-format event the wrapper sends. The backend
// enriches each event with id/orgId/serviceId/envId/instanceId (the last
// from the X-Instance-Key header) before persisting; the wrapper sends the
// fields below verbatim.
//
// Never includes raw facts — only the templated request shape +
// matched-rule outcomes. Mirrors klswitch-console/src/lib/zod/analytics.ts.
// AnalyticsEventTypeEvaluation is the polymorphic discriminator for the
// analytics ingest (POST /v1/analytics). Only evaluation events exist today.
const AnalyticsEventTypeEvaluation = "evaluation"

type AnalyticsEvent struct {
	// Type is the polymorphic discriminator — always "evaluation" for now.
	Type          string          `json:"type"`
	// EventID is a client-generated UUID v4 idempotency key, minted once when
	// the event is recorded and stable for its lifetime. The backend enforces
	// UNIQUE(eventId) so a redelivered batch collapses to one stored decision.
	EventID       string          `json:"eventId"`
	OccurredAt    string          `json:"occurredAt"`
	RulesVersion  int             `json:"rulesVersion"`
	Request       AnalyticsReq    `json:"request"`
	Matched       []MatchedAction `json:"matched"`
	Evaluated     int             `json:"evaluated"`
	Skipped       int             `json:"skipped"`
	LatencyBucket LatencyBucket   `json:"latencyBucket"`
	// MissingFields: schema-drift Layer 3 — deduplicated, sorted list of
	// field paths that matched rules referenced but the request input
	// didn't carry. Always present on the wire (empty list when none)
	// so console queries don't have to special-case null.
	MissingFields []string `json:"missingFields"`
}

// AnalyticsReq is the templated request shape: { "method": "POST", "path": "/v1/payments/:id" }.
type AnalyticsReq struct {
	Method string `json:"method"`
	Path   string `json:"path"`
}

// MatchedAction — one per matched rule, denormalised so the console can
// render badges without joining to the rules table.
type MatchedAction struct {
	RuleID     string `json:"ruleId"`
	ActionType string `json:"actionType"`
}

// AnalyticsSink receives drained batches. When set, the reporter bypasses
// the HTTP POST entirely. Errors are logged + counted; never propagated.
type AnalyticsSink func(events []AnalyticsEvent) error

// AnalyticsOptions configures the reporter. Zero values use the SDK defaults.
// Sink overrides the HTTP transport when non-nil.
type AnalyticsOptions struct {
	Sink          AnalyticsSink
	BatchSize     int
	FlushInterval time.Duration
	MaxQueueSize  int
	PostTimeout   time.Duration
}

func (o *AnalyticsOptions) applyDefaults() {
	if o.BatchSize <= 0 {
		o.BatchSize = DefaultAnalyticsBatchSize
	}
	if o.FlushInterval <= 0 {
		o.FlushInterval = DefaultAnalyticsFlushInterval
	}
	if o.MaxQueueSize <= 0 {
		o.MaxQueueSize = DefaultAnalyticsMaxQueueSize
	}
	if o.PostTimeout <= 0 {
		o.PostTimeout = DefaultAnalyticsPostTimeout
	}
}

// analyticsTransport describes where flushed batches go. Either a custom
// sink (Sink != nil) OR HTTP (URL != ""); the constructor enforces "at
// least one of."
type analyticsTransport struct {
	URL         string
	Headers     map[string]string
	SDKLang     string
	SDKVersion  string
	InstanceKey string
	HTTPClient  *http.Client
	Sink        AnalyticsSink
	PostTimeout time.Duration
}

// AnalyticsStatus is a snapshot of the reporter's health — surfaced via
// Client.AnalyticsStatus() so the console can render drift-visibility
// badges without further coordination.
type AnalyticsStatus struct {
	Enabled             bool   `json:"enabled"`
	BufferSize          int    `json:"bufferSize"`
	BufferCapacity      int    `json:"bufferCapacity"`
	EventsRecorded      int64  `json:"eventsRecorded"`
	EventsDropped       int64  `json:"eventsDropped"`
	EventsSent          int64  `json:"eventsSent"`
	BatchesFailed       int64  `json:"batchesFailed"`
	LastFlushAt         string `json:"lastFlushAt"`         // ISO 8601 or ""
	LastFlushOk         *bool  `json:"lastFlushOk"`         // nil until first flush
	LastFlushError      string `json:"lastFlushError"`      // "" on success
	ConsecutiveFailures int    `json:"consecutiveFailures"`
}

func disabledAnalyticsStatus() AnalyticsStatus {
	return AnalyticsStatus{Enabled: false}
}

// newEventID mints a random UUID v4 for use as an analytics idempotency key.
// Generated at event birth so it stays stable if the batch is redelivered.
// On the vanishingly rare crypto/rand read error it falls back to a
// timestamp token so an event is never left without an id.
func newEventID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("evt-%d", time.Now().UnixNano())
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// LatencyBucketForMillis maps an elapsed-ms duration to the right bucket.
func LatencyBucketForMillis(ms float64) LatencyBucket {
	switch {
	case ms < 0.05:
		return LatencyBucketLt50us
	case ms < 0.5:
		return LatencyBucketLt500us
	case ms < 5:
		return LatencyBucketLt5ms
	default:
		return LatencyBucketGe5ms
	}
}

// latencyBucket — internal helper kept on the duration shape used by client.go.
func latencyBucket(d time.Duration) LatencyBucket {
	return LatencyBucketForMillis(float64(d) / float64(time.Millisecond))
}

// analyticsReporter — bounded ring buffer + single-flight HTTP flush worker.
type analyticsReporter struct {
	transport    analyticsTransport
	batchSize    int
	maxQueueSize int
	postTimeout  time.Duration
	logger       Logger
	httpClient   *http.Client

	mu       sync.Mutex
	buffer   []AnalyticsEvent // ring backing; cap == maxQueueSize
	head     int              // read index
	tail     int              // write index
	size     int

	flushing bool            // single-flight gate
	stopped  bool
	// Counters (mutated under mu).
	eventsRecorded      int64
	eventsDropped       int64
	eventsSent          int64
	batchesFailed       int64
	lastFlushAt         string
	lastFlushOk         *bool
	lastFlushError      string
	consecutiveFailures int

	flushSignal chan struct{} // buffered (1); coalesces nudges from Record
	stop        chan struct{}
	timerDone   chan struct{}
}

func newAnalyticsReporter(opts AnalyticsOptions, transport analyticsTransport, logger Logger) *analyticsReporter {
	opts.applyDefaults()
	if transport.PostTimeout <= 0 {
		transport.PostTimeout = opts.PostTimeout
	}
	if transport.HTTPClient == nil && transport.URL != "" {
		transport.HTTPClient = &http.Client{Timeout: transport.PostTimeout}
	}
	if logger == nil {
		logger = noopLogger{}
	}
	r := &analyticsReporter{
		transport:    transport,
		batchSize:    opts.BatchSize,
		maxQueueSize: opts.MaxQueueSize,
		postTimeout:  transport.PostTimeout,
		logger:       logger,
		httpClient:   transport.HTTPClient,
		buffer:       make([]AnalyticsEvent, opts.MaxQueueSize),
		flushSignal:  make(chan struct{}, 1),
		stop:         make(chan struct{}),
		timerDone:    make(chan struct{}),
	}
	go r.run(opts.FlushInterval)
	return r
}

// Record enqueues an event. Never blocks the caller; never panics.
// On overflow, evicts the oldest event (drop-oldest policy).
func (r *analyticsReporter) Record(e AnalyticsEvent) {
	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		return
	}
	r.eventsRecorded++
	if r.size == r.maxQueueSize {
		// Drop oldest: advance head, decrement size, then write tail.
		r.head = (r.head + 1) % r.maxQueueSize
		r.size--
		r.eventsDropped++
	}
	r.buffer[r.tail] = e
	r.tail = (r.tail + 1) % r.maxQueueSize
	r.size++
	shouldFlush := r.size >= r.batchSize
	r.mu.Unlock()
	if shouldFlush {
		select {
		case r.flushSignal <- struct{}{}:
		default:
		}
	}
}

// Status returns a snapshot of the reporter's health.
func (r *analyticsReporter) Status() AnalyticsStatus {
	r.mu.Lock()
	defer r.mu.Unlock()
	return AnalyticsStatus{
		Enabled:             true,
		BufferSize:          r.size,
		BufferCapacity:      r.maxQueueSize,
		EventsRecorded:      r.eventsRecorded,
		EventsDropped:       r.eventsDropped,
		EventsSent:          r.eventsSent,
		BatchesFailed:       r.batchesFailed,
		LastFlushAt:         r.lastFlushAt,
		LastFlushOk:         r.lastFlushOk,
		LastFlushError:      r.lastFlushError,
		ConsecutiveFailures: r.consecutiveFailures,
	}
}

// flush drains one batch and ships it. Single-flight: a concurrent caller
// returns early; the next flush picks up where this one left off.
func (r *analyticsReporter) flush() {
	r.mu.Lock()
	if r.flushing || r.stopped {
		r.mu.Unlock()
		return
	}
	if r.size == 0 {
		r.mu.Unlock()
		return
	}
	r.flushing = true
	batch := r.drainLocked(r.batchSize)
	r.mu.Unlock()

	ok := r.ship(batch)
	now := time.Now().UTC().Format(time.RFC3339Nano)

	r.mu.Lock()
	r.flushing = false
	r.lastFlushAt = now
	r.lastFlushOk = &ok
	if ok {
		r.eventsSent += int64(len(batch))
		r.consecutiveFailures = 0
		r.lastFlushError = ""
	} else {
		r.batchesFailed++
		r.consecutiveFailures++
	}
	r.mu.Unlock()
}

// FlushNow forces a single-batch flush synchronously (mostly for tests + close).
func (r *analyticsReporter) FlushNow() {
	r.flush()
}

func (r *analyticsReporter) drainLocked(max int) []AnalyticsEvent {
	if max > r.size {
		max = r.size
	}
	out := make([]AnalyticsEvent, max)
	for i := 0; i < max; i++ {
		out[i] = r.buffer[r.head]
		// Zero the slot to release any references in the event for GC.
		r.buffer[r.head] = AnalyticsEvent{}
		r.head = (r.head + 1) % r.maxQueueSize
	}
	r.size -= max
	return out
}

// ship returns true on 2xx; false on any failure (sink panic, non-2xx, network, timeout).
// Counters + lastFlushError are mutated under r.mu by the caller (flush()).
func (r *analyticsReporter) ship(batch []AnalyticsEvent) bool {
	if r.transport.Sink != nil {
		if err := safelyCall(r.transport.Sink, batch); err != nil {
			r.mu.Lock()
			r.lastFlushError = err.Error()
			r.mu.Unlock()
			r.logger.Warn("analytics sink threw; dropping batch",
				map[string]any{"error": err.Error(), "dropped": len(batch)})
			return false
		}
		return true
	}
	if r.transport.URL == "" {
		r.mu.Lock()
		r.lastFlushError = "no analytics transport configured"
		r.mu.Unlock()
		return false
	}
	return r.shipHTTP(batch)
}

func (r *analyticsReporter) shipHTTP(batch []AnalyticsEvent) bool {
	envelope := struct {
		SDKLang     string           `json:"sdkLang"`
		SDKVersion  string           `json:"sdkVersion"`
		InstanceKey string           `json:"instanceKey"`
		Events      []AnalyticsEvent `json:"events"`
	}{r.transport.SDKLang, r.transport.SDKVersion, r.transport.InstanceKey, batch}

	jsonBytes, err := json.Marshal(envelope)
	if err != nil {
		r.mu.Lock()
		r.lastFlushError = "serialise failed: " + err.Error()
		r.mu.Unlock()
		r.logger.Warn("analytics serialise failed", map[string]any{"error": err.Error()})
		return false
	}

	var body bytes.Buffer
	gz := gzip.NewWriter(&body)
	if _, err := gz.Write(jsonBytes); err != nil {
		_ = gz.Close()
		r.mu.Lock()
		r.lastFlushError = "gzip failed: " + err.Error()
		r.mu.Unlock()
		return false
	}
	if err := gz.Close(); err != nil {
		r.mu.Lock()
		r.lastFlushError = "gzip close failed: " + err.Error()
		r.mu.Unlock()
		return false
	}

	ctx, cancel := context.WithTimeout(context.Background(), r.postTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.transport.URL, &body)
	if err != nil {
		r.mu.Lock()
		r.lastFlushError = "build request failed: " + err.Error()
		r.mu.Unlock()
		return false
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Encoding", "gzip")
	for k, v := range r.transport.Headers {
		req.Header.Set(k, v)
	}

	resp, err := r.httpClient.Do(req)
	if err != nil {
		r.mu.Lock()
		r.lastFlushError = err.Error()
		failures := r.consecutiveFailures + 1
		r.mu.Unlock()
		r.logger.Warn("analytics POST failed; dropping batch", map[string]any{
			"url":                 r.transport.URL,
			"error":               err.Error(),
			"dropped":             len(batch),
			"consecutiveFailures": failures,
		})
		return false
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		r.mu.Lock()
		r.lastFlushError = "analytics POST returned status " + http.StatusText(resp.StatusCode)
		r.mu.Unlock()
		r.logger.Warn("analytics POST rejected", map[string]any{
			"url":     r.transport.URL,
			"status":  resp.StatusCode,
			"dropped": len(batch),
		})
		return false
	}
	return true
}

// safelyCall recovers from sink panics so a buggy operator-supplied sink
// can't kill the analytics goroutine.
func safelyCall(sink AnalyticsSink, batch []AnalyticsEvent) (err error) {
	defer func() {
		if r := recover(); r != nil {
			if e, ok := r.(error); ok {
				err = e
			} else {
				err = analyticsSinkPanic{value: r}
			}
		}
	}()
	return sink(batch)
}

type analyticsSinkPanic struct{ value any }

func (p analyticsSinkPanic) Error() string {
	return "analytics sink panicked"
}

func (r *analyticsReporter) run(interval time.Duration) {
	defer close(r.timerDone)
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-t.C:
			r.flush()
		case <-r.flushSignal:
			r.flush()
		case <-r.stop:
			return
		}
	}
}

// Close stops the background goroutine, waits for any in-flight flush to
// finish, then makes one best-effort final drain on the caller's goroutine.
// Idempotent.
func (r *analyticsReporter) Close() {
	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		return
	}
	r.stopped = true
	r.mu.Unlock()
	close(r.stop)
	<-r.timerDone
	// One final drain on the caller's goroutine — bypass the stopped flag
	// by clearing it temporarily; this is the only place we run a flush
	// from outside the worker.
	r.mu.Lock()
	r.stopped = false
	r.mu.Unlock()
	r.flush()
	r.mu.Lock()
	r.stopped = true
	r.mu.Unlock()
}
