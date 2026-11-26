package klswitch

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// B1 — ETag + Cache-Control polling tests. Drives the real HTTPFetcher
// (and pollingManager) against httptest servers so the HEAD/GET dance,
// conditional-GET, and Cache-Control parsing all see real HTTP semantics.

// rulesetJSON is a minimal valid ruleset body — content doesn't matter for
// these tests, only the wire dance does.
const rulesetJSON = `{"schema_version":"1.0","rules_version":1,"rules":[]}`

// etagServer is a test HTTPServer that records requests and replies from a
// FIFO queue of canned responses. The last queued reply sticks for any
// subsequent calls (mirrors the Node etag test fixture).
type etagServer struct {
	srv         *httptest.Server
	mu          sync.Mutex
	methods     []string
	ifNoneMatch []string
	replies     []etagReply
	cursor      int
}

type etagReply struct {
	status       int
	etag         string // empty → no ETag header
	cacheControl string // empty → no Cache-Control header
	body         string // ignored for HEAD and 304
}

func newEtagServer(replies ...etagReply) *etagServer {
	es := &etagServer{replies: replies}
	es.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		es.mu.Lock()
		es.methods = append(es.methods, r.Method)
		es.ifNoneMatch = append(es.ifNoneMatch, r.Header.Get("If-None-Match"))
		idx := es.cursor
		if idx >= len(es.replies) {
			idx = len(es.replies) - 1
		} else {
			es.cursor++
		}
		reply := es.replies[idx]
		es.mu.Unlock()

		if reply.etag != "" {
			w.Header().Set("ETag", reply.etag)
		}
		if reply.cacheControl != "" {
			w.Header().Set("Cache-Control", reply.cacheControl)
		}
		w.WriteHeader(reply.status)
		if r.Method != http.MethodHead && reply.status != http.StatusNotModified {
			_, _ = w.Write([]byte(reply.body))
		}
	}))
	return es
}

func (es *etagServer) close() { es.srv.Close() }

func (es *etagServer) recordedMethods() []string {
	es.mu.Lock()
	defer es.mu.Unlock()
	out := make([]string, len(es.methods))
	copy(out, es.methods)
	return out
}

func (es *etagServer) recordedIfNoneMatch() []string {
	es.mu.Lock()
	defer es.mu.Unlock()
	out := make([]string, len(es.ifNoneMatch))
	copy(out, es.ifNoneMatch)
	return out
}

func newHTTPFetcher(url string) *HTTPFetcher {
	return &HTTPFetcher{
		URL:     url,
		Headers: map[string]string{"Authorization": "Bearer t"},
		Timeout: 2 * time.Second,
	}
}

// ─── HEAD-first / ETag dedup ─────────────────────────────────────────────

func TestEtag_FirstFetchIsGet_thenSubsequentFetchesAreHead(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30"}, // HEAD match
	)
	defer srv.close()

	f := newHTTPFetcher(srv.srv.URL)
	r1, err := f.FetchWithMeta(context.Background())
	if err != nil {
		t.Fatalf("first fetch: %v", err)
	}
	r2, err := f.FetchWithMeta(context.Background())
	if err != nil {
		t.Fatalf("second fetch: %v", err)
	}

	methods := srv.recordedMethods()
	if len(methods) != 2 || methods[0] != "GET" || methods[1] != "HEAD" {
		t.Errorf("expected [GET HEAD], got %v", methods)
	}
	if r1.NotModified {
		t.Error("cold GET should deliver a payload, not NotModified")
	}
	if !r2.NotModified {
		t.Error("HEAD ETag match → NotModified=true expected")
	}
	if r2.ETag != `"v1"` {
		t.Errorf("ETag: got %q", r2.ETag)
	}
}

func TestEtag_HeadEtagFlipTriggersConditionalGet(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
		etagReply{status: 200, etag: `"v2"`, cacheControl: "max-age=30"},                  // HEAD: flip
		etagReply{status: 200, etag: `"v2"`, cacheControl: "max-age=30", body: rulesetJSON}, // conditional GET
	)
	defer srv.close()

	f := newHTTPFetcher(srv.srv.URL)
	_, _ = f.FetchWithMeta(context.Background())
	r2, err := f.FetchWithMeta(context.Background())
	if err != nil {
		t.Fatalf("second fetch: %v", err)
	}

	methods := srv.recordedMethods()
	if len(methods) != 3 || methods[0] != "GET" || methods[1] != "HEAD" || methods[2] != "GET" {
		t.Errorf("expected [GET HEAD GET], got %v", methods)
	}
	inm := srv.recordedIfNoneMatch()
	if inm[2] != `"v1"` {
		t.Errorf("conditional GET should carry If-None-Match: \"v1\"; got %q", inm[2])
	}
	if r2.NotModified || r2.ETag != `"v2"` {
		t.Errorf("expected fresh body with ETag v2; got %+v", r2)
	}
}

func TestEtag_ConditionalGet304IsNoOp(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
		etagReply{status: 200, etag: `"v2"`, cacheControl: "max-age=30"},         // HEAD: claims new
		etagReply{status: 304, etag: `"v1"`, cacheControl: "max-age=30"},         // race: server reverted
	)
	defer srv.close()

	f := newHTTPFetcher(srv.srv.URL)
	_, _ = f.FetchWithMeta(context.Background())
	r2, err := f.FetchWithMeta(context.Background())
	if err != nil {
		t.Fatalf("second fetch: %v", err)
	}
	if !r2.NotModified {
		t.Error("304 → NotModified=true expected")
	}
}

// ─── Cache-Control max-age tuning ────────────────────────────────────────

func TestEtag_CacheControlPopulatesNextDelay(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=120", body: rulesetJSON},
	)
	defer srv.close()

	r, err := newHTTPFetcher(srv.srv.URL).FetchWithMeta(context.Background())
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if r.NextDelay == nil || *r.NextDelay != 120*time.Second {
		t.Errorf("NextDelay: got %v, want 2m", r.NextDelay)
	}
}

func TestEtag_ExtraCacheControlDirectivesTolerated(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "public, max-age=45, must-revalidate", body: rulesetJSON},
	)
	defer srv.close()

	r, _ := newHTTPFetcher(srv.srv.URL).FetchWithMeta(context.Background())
	if r.NextDelay == nil || *r.NextDelay != 45*time.Second {
		t.Errorf("NextDelay: got %v, want 45s", r.NextDelay)
	}
}

func TestEtag_NoCacheControlMeansNilNextDelay(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, body: rulesetJSON},
	)
	defer srv.close()

	r, _ := newHTTPFetcher(srv.srv.URL).FetchWithMeta(context.Background())
	if r.NextDelay != nil {
		t.Errorf("NextDelay should be nil when header absent; got %v", *r.NextDelay)
	}
}

// ─── pollingManager: server-driven schedule ──────────────────────────────

func TestEtag_PollingStatusReflectsLastEtagAndCurrentInterval(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v9"`, cacheControl: "max-age=120", body: rulesetJSON},
	)
	defer srv.close()

	fetched := atomic.Int32{}
	pm := newPollingManager(newHTTPFetcher(srv.srv.URL), 30*time.Second, noopLogger{},
		func(context.Context, RuleSetPayload) { fetched.Add(1) },
		func(error) {},
	)
	pm.PollOnce(context.Background())

	st := pm.Status()
	if st.LastEtag != `"v9"` {
		t.Errorf("LastEtag: got %q", st.LastEtag)
	}
	if st.CurrentInterval != 120*time.Second {
		t.Errorf("CurrentInterval: got %v, want 2m (server-driven)", st.CurrentInterval)
	}
	if fetched.Load() != 1 {
		t.Errorf("onFetched count: got %d, want 1", fetched.Load())
	}
}

func TestEtag_PollingClampsMaxAgeZeroToMin(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=0", body: rulesetJSON},
	)
	defer srv.close()

	pm := newPollingManager(newHTTPFetcher(srv.srv.URL), 30*time.Second, noopLogger{},
		func(context.Context, RuleSetPayload) {}, func(error) {})
	pm.PollOnce(context.Background())
	if pm.Status().CurrentInterval != MinPollInterval {
		t.Errorf("max-age=0 must clamp to MinPollInterval; got %v", pm.Status().CurrentInterval)
	}
}

func TestEtag_PollingClampsEnormousMaxAgeToMax(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=999999", body: rulesetJSON},
	)
	defer srv.close()

	pm := newPollingManager(newHTTPFetcher(srv.srv.URL), 30*time.Second, noopLogger{},
		func(context.Context, RuleSetPayload) {}, func(error) {})
	pm.PollOnce(context.Background())
	if pm.Status().CurrentInterval != MaxPollInterval {
		t.Errorf("enormous max-age must clamp to MaxPollInterval; got %v", pm.Status().CurrentInterval)
	}
}

func TestEtag_PollingSkipsOnFetchedWhenServerSaysNoChange(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30"}, // HEAD match
	)
	defer srv.close()

	fetched := atomic.Int32{}
	pm := newPollingManager(newHTTPFetcher(srv.srv.URL), 30*time.Second, noopLogger{},
		func(context.Context, RuleSetPayload) { fetched.Add(1) },
		func(error) {})
	pm.PollOnce(context.Background())
	pm.PollOnce(context.Background())
	if fetched.Load() != 1 {
		t.Errorf("HEAD match → no second onFetched; got count=%d", fetched.Load())
	}
}

// ─── Graceful degradation (server omits headers) ─────────────────────────

func TestEtag_NoEtagOnResponseMeansEveryPollIsAColdGet(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, body: rulesetJSON}, // no ETag, no Cache-Control — sticks
	)
	defer srv.close()

	f := newHTTPFetcher(srv.srv.URL)
	_, _ = f.FetchWithMeta(context.Background())
	_, _ = f.FetchWithMeta(context.Background())
	_, _ = f.FetchWithMeta(context.Background())

	methods := srv.recordedMethods()
	if len(methods) != 3 || methods[0] != "GET" || methods[1] != "GET" || methods[2] != "GET" {
		t.Errorf("no cached ETag → no HEAD optimisation; got %v", methods)
	}
}

func TestEtag_LegacyFetchAlwaysIssuesPlainGet(t *testing.T) {
	srv := newEtagServer(
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
		etagReply{status: 200, etag: `"v1"`, cacheControl: "max-age=30", body: rulesetJSON},
	)
	defer srv.close()

	f := newHTTPFetcher(srv.srv.URL)
	_, _ = f.Fetch(context.Background())
	_, _ = f.Fetch(context.Background())

	methods := srv.recordedMethods()
	if len(methods) != 2 || methods[0] != "GET" || methods[1] != "GET" {
		t.Errorf("legacy Fetch never uses HEAD; got %v", methods)
	}
}

// ─── Status before any poll ──────────────────────────────────────────────

func TestEtag_PollingStatusBeforeAnyFetch(t *testing.T) {
	pm := newPollingManager(
		fetcherFn(func(context.Context) (RuleSetPayload, error) {
			return RuleSetPayload{}, nil
		}),
		30*time.Second, noopLogger{},
		func(context.Context, RuleSetPayload) {}, func(error) {})
	st := pm.Status()
	if st.LastEtag != "" {
		t.Errorf("LastEtag: got %q", st.LastEtag)
	}
	if !st.NextPollAt.IsZero() {
		t.Errorf("NextPollAt: expected zero, got %v", st.NextPollAt)
	}
	if st.CurrentInterval != 30*time.Second {
		t.Errorf("CurrentInterval: got %v, want bootstrap=30s", st.CurrentInterval)
	}
}

// fetcherFn adapts a plain function to the RulesFetcher interface (no
// FetchWithMeta — exercises the legacy-only fallback path in pollingManager).
type fetcherFn func(context.Context) (RuleSetPayload, error)

func (f fetcherFn) Fetch(ctx context.Context) (RuleSetPayload, error) { return f(ctx) }
