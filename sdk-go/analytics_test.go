package klswitch

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func newEvent() AnalyticsEvent {
	return AnalyticsEvent{
		Type:          AnalyticsEventTypeEvaluation,
		OccurredAt:    time.Now().UTC().Format(time.RFC3339Nano),
		RulesVersion:  1,
		Request:       AnalyticsReq{Method: "POST", Path: "/p"},
		Matched:       nil,
		Evaluated:     0,
		Skipped:       0,
		LatencyBucket: LatencyBucketLt500us,
	}
}

func taggedEvent(tag string) AnalyticsEvent {
	e := newEvent()
	e.OccurredAt = tag
	return e
}

// Regression: on a no-match evaluation, matched + missingFields must serialize
// as `[]`, never `null`. The backend enforces @NotNull on both; a null value
// gets the whole batch rejected with 422. (Go marshals nil slices as null, so
// the client builds matched via make(...) and computeMissingFields returns a
// non-nil empty slice.)
func TestAnalytics_EmptySlicesMarshalAsArrays(t *testing.T) {
	matched := make([]MatchedAction, 0) // as client.go builds it
	missing := computeMissingFields(nil, map[string]Rule{}, EvaluationInput{})
	e := AnalyticsEvent{
		Type:          AnalyticsEventTypeEvaluation,
		EventID:       newEventID(),
		OccurredAt:    "2026-01-01T00:00:00Z",
		RulesVersion:  1,
		Request:       AnalyticsReq{Method: "GET", Path: "/x"},
		Matched:       matched,
		LatencyBucket: LatencyBucketLt50us,
		MissingFields: missing,
	}
	raw, err := json.Marshal(e)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if bytes.Contains(raw, []byte(`"matched":null`)) || bytes.Contains(raw, []byte(`"missingFields":null`)) {
		t.Errorf("empty slice marshaled as null (would 422 the batch): %s", raw)
	}
	if !bytes.Contains(raw, []byte(`"matched":[]`)) || !bytes.Contains(raw, []byte(`"missingFields":[]`)) {
		t.Errorf("expected matched:[] and missingFields:[], got: %s", raw)
	}
}

func TestLatencyBucket_NewEnum(t *testing.T) {
	cases := map[time.Duration]LatencyBucket{
		10 * time.Microsecond:  LatencyBucketLt50us,
		100 * time.Microsecond: LatencyBucketLt500us,
		2 * time.Millisecond:   LatencyBucketLt5ms,
		50 * time.Millisecond:  LatencyBucketGe5ms,
	}
	for d, want := range cases {
		if got := latencyBucket(d); got != want {
			t.Errorf("latencyBucket(%v) = %q, want %q", d, got, want)
		}
	}
}

func TestAnalytics_RecordThenFlushDeliversBatches(t *testing.T) {
	var (
		mu      sync.Mutex
		batches [][]AnalyticsEvent
	)
	sink := func(events []AnalyticsEvent) error {
		mu.Lock()
		defer mu.Unlock()
		batches = append(batches, append([]AnalyticsEvent(nil), events...))
		return nil
	}
	r := newAnalyticsReporter(
		AnalyticsOptions{Sink: sink, BatchSize: 3, FlushInterval: 50 * time.Millisecond},
		analyticsTransport{SDKLang: "go", SDKVersion: "0.1.0", InstanceKey: "i", Sink: sink},
		noopLogger{},
	)
	t.Cleanup(r.Close)

	for i := 0; i < 5; i++ {
		r.Record(newEvent())
	}
	if !waitFor(t, 2*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		n := 0
		for _, b := range batches {
			n += len(b)
		}
		return n == 5
	}) {
		t.Fatalf("expected 5 events flushed; got batches=%v", batches)
	}
}

func TestAnalytics_OverflowDropsOldestKeepsMostRecent(t *testing.T) {
	var (
		mu  sync.Mutex
		seen []AnalyticsEvent
	)
	sink := func(events []AnalyticsEvent) error {
		mu.Lock()
		seen = append(seen, events...)
		mu.Unlock()
		return nil
	}
	// Big batch + slow timer so the buffer fills before any flush.
	r := newAnalyticsReporter(
		AnalyticsOptions{Sink: sink, BatchSize: 100, FlushInterval: time.Hour, MaxQueueSize: 2},
		analyticsTransport{SDKLang: "go", SDKVersion: "0.1.0", InstanceKey: "i", Sink: sink},
		noopLogger{},
	)
	t.Cleanup(r.Close)

	for i := 0; i < 5; i++ {
		r.Record(taggedEvent("evt-" + string(rune('0'+i))))
	}
	r.FlushNow()

	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 2 {
		t.Fatalf("expected buffer to cap at 2; got %d events", len(seen))
	}
	if seen[0].OccurredAt != "evt-3" || seen[1].OccurredAt != "evt-4" {
		t.Errorf("drop-oldest expected evt-3 + evt-4; got %v / %v",
			seen[0].OccurredAt, seen[1].OccurredAt)
	}
	if got := r.Status().EventsDropped; got != 3 {
		t.Errorf("expected eventsDropped=3, got %d", got)
	}
}

func TestAnalytics_StatusSnapshotsCounters(t *testing.T) {
	sink := func(events []AnalyticsEvent) error { return nil }
	r := newAnalyticsReporter(
		AnalyticsOptions{Sink: sink, BatchSize: 100, FlushInterval: time.Hour},
		analyticsTransport{SDKLang: "go", SDKVersion: "0.1.0", InstanceKey: "i", Sink: sink},
		noopLogger{},
	)
	t.Cleanup(r.Close)

	for i := 0; i < 3; i++ {
		r.Record(newEvent())
	}
	before := r.Status()
	if before.BufferSize != 3 || before.EventsRecorded != 3 || before.EventsSent != 0 {
		t.Errorf("unexpected before: %+v", before)
	}
	r.FlushNow()
	after := r.Status()
	if after.BufferSize != 0 || after.EventsSent != 3 {
		t.Errorf("unexpected after: %+v", after)
	}
	if after.LastFlushOk == nil || !*after.LastFlushOk {
		t.Errorf("expected LastFlushOk=true; got %v", after.LastFlushOk)
	}
	if after.ConsecutiveFailures != 0 {
		t.Errorf("expected 0 failures; got %d", after.ConsecutiveFailures)
	}
}

func TestAnalytics_SinkErrorCountsAsFailure(t *testing.T) {
	calls := atomic.Int32{}
	sink := func(events []AnalyticsEvent) error {
		calls.Add(1)
		return errors.New("sink boom")
	}
	r := newAnalyticsReporter(
		AnalyticsOptions{Sink: sink, BatchSize: 1, FlushInterval: time.Hour},
		analyticsTransport{SDKLang: "go", SDKVersion: "0.1.0", InstanceKey: "i", Sink: sink},
		noopLogger{},
	)
	t.Cleanup(r.Close)

	r.Record(newEvent())
	r.FlushNow()
	st := r.Status()
	if st.EventsSent != 0 {
		t.Errorf("expected EventsSent=0; got %d", st.EventsSent)
	}
	if st.BatchesFailed != 1 {
		t.Errorf("expected BatchesFailed=1; got %d", st.BatchesFailed)
	}
	if st.LastFlushOk == nil || *st.LastFlushOk {
		t.Errorf("expected LastFlushOk=false; got %v", st.LastFlushOk)
	}
	if st.LastFlushError == "" {
		t.Errorf("expected LastFlushError to be set")
	}
}

func TestAnalytics_HTTPTransportPostsGzippedEnvelope(t *testing.T) {
	var (
		mu    sync.Mutex
		bodies [][]byte
		headers []http.Header
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		raw, _ := io.ReadAll(req.Body)
		mu.Lock()
		bodies = append(bodies, raw)
		headers = append(headers, req.Header.Clone())
		mu.Unlock()
		w.WriteHeader(204)
	}))
	defer srv.Close()

	tr := analyticsTransport{
		URL:         srv.URL + "/v1/decisions",
		Headers:     map[string]string{"Authorization": "Bearer t", "X-Instance-Key": "pod-1"},
		SDKLang:     "go",
		SDKVersion:  "0.1.0",
		InstanceKey: "pod-1",
		PostTimeout: 5 * time.Second,
	}
	r := newAnalyticsReporter(
		AnalyticsOptions{BatchSize: 2, FlushInterval: time.Hour},
		tr, noopLogger{},
	)
	t.Cleanup(r.Close)

	r.Record(AnalyticsEvent{
		Type: AnalyticsEventTypeEvaluation,
		OccurredAt: time.Now().UTC().Format(time.RFC3339Nano), RulesVersion: 1,
		Request: AnalyticsReq{Method: "POST", Path: "/p"},
		Matched: []MatchedAction{{RuleID: "r1", ActionType: "block"}},
		Evaluated: 1, LatencyBucket: LatencyBucketLt500us,
	})
	r.Record(AnalyticsEvent{
		Type: AnalyticsEventTypeEvaluation,
		OccurredAt: time.Now().UTC().Format(time.RFC3339Nano), RulesVersion: 1,
		Request: AnalyticsReq{Method: "POST", Path: "/p"},
		Matched: []MatchedAction{{RuleID: "r2", ActionType: "notify"}},
		Evaluated: 1, LatencyBucket: LatencyBucketLt500us,
	})
	if !waitFor(t, 2*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(bodies) >= 1
	}) {
		t.Fatalf("expected POST to land; got 0")
	}
	mu.Lock()
	defer mu.Unlock()

	enc := headers[0].Get("Content-Encoding")
	if enc != "gzip" {
		t.Errorf("expected gzip; got %q", enc)
	}
	gz, err := gzip.NewReader(bytes.NewReader(bodies[0]))
	if err != nil {
		t.Fatalf("gzip reader: %v", err)
	}
	defer gz.Close()
	decompressed, _ := io.ReadAll(gz)
	var env struct {
		SDKLang     string           `json:"sdkLang"`
		SDKVersion  string           `json:"sdkVersion"`
		InstanceKey string           `json:"instanceKey"`
		Events      []AnalyticsEvent `json:"events"`
	}
	if err := json.Unmarshal(decompressed, &env); err != nil {
		t.Fatalf("decode envelope: %v (raw=%s)", err, string(decompressed))
	}
	if env.SDKLang != "go" || env.SDKVersion != "0.1.0" || env.InstanceKey != "pod-1" {
		t.Errorf("envelope mismatch: %+v", env)
	}
	if len(env.Events) != 2 {
		t.Fatalf("expected 2 events; got %d", len(env.Events))
	}
	if env.Events[0].Matched[0].RuleID != "r1" {
		t.Errorf("expected first event to be r1; got %s", env.Events[0].Matched[0].RuleID)
	}
	if env.Events[0].Type != "evaluation" {
		t.Errorf("expected event type=evaluation; got %q", env.Events[0].Type)
	}
}

func TestAnalytics_HTTPNon2xxCountsAsFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(500)
	}))
	defer srv.Close()
	tr := analyticsTransport{
		URL:         srv.URL + "/v1/decisions",
		SDKLang:     "go", SDKVersion: "0.1.0", InstanceKey: "pod-1",
		PostTimeout: 5 * time.Second,
	}
	r := newAnalyticsReporter(
		AnalyticsOptions{BatchSize: 1, FlushInterval: time.Hour},
		tr, noopLogger{},
	)
	t.Cleanup(r.Close)
	r.Record(newEvent())
	r.FlushNow()
	st := r.Status()
	if st.EventsSent != 0 {
		t.Errorf("expected EventsSent=0; got %d", st.EventsSent)
	}
	if st.BatchesFailed < 1 {
		t.Errorf("expected ≥1 failed batch; got %d", st.BatchesFailed)
	}
}

func TestAnalytics_CloseDrainsRemaining(t *testing.T) {
	var (
		mu   sync.Mutex
		seen int
	)
	sink := func(events []AnalyticsEvent) error {
		mu.Lock()
		seen += len(events)
		mu.Unlock()
		return nil
	}
	r := newAnalyticsReporter(
		AnalyticsOptions{Sink: sink, BatchSize: 1000, FlushInterval: time.Hour, MaxQueueSize: 100},
		analyticsTransport{SDKLang: "go", SDKVersion: "0.1.0", InstanceKey: "i", Sink: sink},
		noopLogger{},
	)
	for i := 0; i < 7; i++ {
		r.Record(newEvent())
	}
	r.Close()
	mu.Lock()
	defer mu.Unlock()
	if seen != 7 {
		t.Fatalf("Close should drain all queued events; saw %d / 7", seen)
	}
}

// waitFor polls pred up to timeout. Returns true once pred is true.
func waitFor(t *testing.T, timeout time.Duration, pred func() bool) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if pred() {
			return true
		}
		time.Sleep(10 * time.Millisecond)
	}
	return false
}
