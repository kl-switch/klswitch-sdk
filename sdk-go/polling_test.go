package klswitch

import (
	"context"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeFetcher is a programmable RulesFetcher for unit tests. The returned func
// controls each call; counters and history live alongside.
type fakeFetcher struct {
	mu      sync.Mutex
	calls   int32
	respond func(call int32) (RuleSetPayload, error)
}

func (f *fakeFetcher) Fetch(ctx context.Context) (RuleSetPayload, error) {
	n := atomic.AddInt32(&f.calls, 1)
	return f.respond(n)
}

func (f *fakeFetcher) callCount() int32 { return atomic.LoadInt32(&f.calls) }

func TestPolling_PollOnceCallsOnFetched(t *testing.T) {
	fetcher := &fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
		return RuleSetPayload{SchemaVersion: "1.0", RulesVersion: 7}, nil
	}}
	var got RuleSetPayload
	p := newPollingManager(fetcher, time.Hour, noopLogger{},
		func(_ context.Context, rs RuleSetPayload) { got = rs },
		func(error) { t.Errorf("unexpected onError") },
	)
	p.PollOnce(context.Background())
	if got.SchemaVersion != "1.0" {
		t.Fatalf("expected onFetched to receive payload; got %+v", got)
	}
}

func TestPolling_FetchFailureCallsOnError(t *testing.T) {
	fetcher := &fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
		return RuleSetPayload{}, errors.New("network down")
	}}
	var sawErr error
	p := newPollingManager(fetcher, time.Hour, noopLogger{},
		func(context.Context, RuleSetPayload) { t.Errorf("unexpected onFetched") },
		func(err error) { sawErr = err },
	)
	p.PollOnce(context.Background())
	if sawErr == nil || !strings.Contains(sawErr.Error(), "network down") {
		t.Fatalf("expected network-down error, got %v", sawErr)
	}
}

// Recurring polls actually fire and the loop terminates cleanly via Close.
func TestPolling_LoopFiresAndClosesCleanly(t *testing.T) {
	fetcher := &fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
		return RuleSetPayload{SchemaVersion: "1.0", RulesVersion: 1}, nil
	}}
	p := newPollingManager(fetcher, 30*time.Millisecond, noopLogger{},
		func(context.Context, RuleSetPayload) {},
		func(error) {},
	)
	p.Start(context.Background())
	if !waitFor(t, 2*time.Second, func() bool { return fetcher.callCount() >= 3 }) {
		t.Fatalf("expected >=3 polls, saw %d", fetcher.callCount())
	}
	p.Close() // must not hang
}

// Consecutive failures trigger exponential backoff (next delay >> base interval).
func TestPolling_BackoffOnConsecutiveFailures(t *testing.T) {
	fetcher := &fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
		return RuleSetPayload{}, errors.New("boom")
	}}
	p := newPollingManager(fetcher, 100*time.Millisecond, noopLogger{},
		func(context.Context, RuleSetPayload) {},
		func(error) {},
	)
	// Simulate three consecutive failures.
	for i := 0; i < 3; i++ {
		p.PollOnce(context.Background())
	}
	delay := p.nextDelay()
	// 100ms * 2^3 = 800ms; we want at least > base.
	if delay <= 100*time.Millisecond {
		t.Errorf("expected backoff > 100ms after 3 failures, got %v", delay)
	}
	if delay > MaxBackoffInterval {
		t.Errorf("delay should not exceed MaxBackoffInterval (%v), got %v", MaxBackoffInterval, delay)
	}
}

// B2: a long server cadence (Cache-Control: max-age) is never undercut by
// the backoff cap. With a 10-min cadence the failure-side delay must stay
// >= the success cadence (modulo jitter), not collapse to the 5-min hard
// cap.
func TestPolling_LongServerCadenceIsNeverUndercutByCap(t *testing.T) {
	// Drive currentInterval directly to simulate a long server cadence
	// without spinning up the full fetcher stack.
	p := newPollingManager(
		&fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
			return RuleSetPayload{}, errors.New("boom")
		}},
		30*time.Second,
		noopLogger{},
		func(context.Context, RuleSetPayload) {}, func(error) {},
	)
	p.mu.Lock()
	p.currentInterval = 10 * time.Minute
	p.consecutiveFailures = 3
	p.mu.Unlock()
	delay := p.nextDelay()
	// With ±25 % jitter the lower bound is 0.75 * 10min = 7.5 min.
	if delay < 7*time.Minute+30*time.Second {
		t.Errorf("backoff undercut the success cadence: got %v, want >= ~7.5m", delay)
	}
}

// A success resets the failure counter (next delay returns to base interval).
func TestPolling_SuccessResetsBackoff(t *testing.T) {
	mode := atomic.Int32{}
	fetcher := &fakeFetcher{respond: func(int32) (RuleSetPayload, error) {
		if mode.Load() == 0 {
			return RuleSetPayload{}, errors.New("boom")
		}
		return RuleSetPayload{SchemaVersion: "1.0", RulesVersion: 1}, nil
	}}
	p := newPollingManager(fetcher, 100*time.Millisecond, noopLogger{},
		func(context.Context, RuleSetPayload) {},
		func(error) {},
	)
	p.PollOnce(context.Background())
	p.PollOnce(context.Background())
	if p.nextDelay() == 100*time.Millisecond {
		t.Fatalf("backoff should be > base after failures")
	}
	mode.Store(1)
	p.PollOnce(context.Background())
	if p.nextDelay() != 100*time.Millisecond {
		t.Errorf("success should reset backoff to base interval; got %v", p.nextDelay())
	}
}

func TestHTTPFetcher_DefaultTimeoutIsApplied(t *testing.T) {
	f := &HTTPFetcher{URL: "http://invalid.invalid"}
	if f.Timeout != 0 {
		t.Fatalf("test assumes zero timeout; got %v", f.Timeout)
	}
	// We just verify the default constant is reasonable; the actual fetch
	// would fail (invalid host), and the integration test exercises HTTP
	// round-trips against a real server.
	if DefaultRefreshTimeout != 5*time.Second {
		t.Errorf("expected DefaultRefreshTimeout=5s, got %v", DefaultRefreshTimeout)
	}
}

// pollingManager.Close must interrupt a slow in-flight Fetch via context
// cancellation — not wait for it to time out / complete on its own. The fetcher
// here blocks until either `release` fires (test wins) or the ctx is cancelled
// (Close wins, which is what we want).
type blockingFetcher struct {
	started chan struct{}
	release chan struct{}
}

func (b *blockingFetcher) Fetch(ctx context.Context) (RuleSetPayload, error) {
	close(b.started)
	select {
	case <-b.release:
		return RuleSetPayload{SchemaVersion: "1.0", RulesVersion: 1}, nil
	case <-ctx.Done():
		return RuleSetPayload{}, ctx.Err()
	}
}

func TestPolling_CloseInterruptsSlowFetch(t *testing.T) {
	bf := &blockingFetcher{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	t.Cleanup(func() { close(bf.release) })

	p := newPollingManager(bf, 10*time.Millisecond, noopLogger{},
		func(context.Context, RuleSetPayload) {},
		func(error) {},
	)
	p.Start(context.Background())

	// Wait for the first Fetch to be in flight.
	select {
	case <-bf.started:
	case <-time.After(2 * time.Second):
		t.Fatalf("fetch never started")
	}

	// Close must propagate ctx cancellation into the Fetch — and therefore
	// return promptly, NOT wait for a default 5 s HTTP timeout.
	start := time.Now()
	p.Close()
	elapsed := time.Since(start)
	if elapsed > 1*time.Second {
		t.Fatalf("Close should cancel in-flight Fetch via context; took %v", elapsed)
	}
}
