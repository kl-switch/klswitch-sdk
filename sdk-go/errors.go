package klswitch

import "errors"

// Package-level sentinel errors for config validation. Returned by [Init] when
// the supplied options are inconsistent.
var (
	errTokenRequired          = errors.New("KLSwitch: token is required (use WithToken; or supply WithRules / WithFetcher for dev/test paths)")
	errInvalidRefreshInterval = errors.New("KLSwitch: refreshInterval must be >= 0 (pass 0 to disable polling)")
	errInvalidTimeout         = errors.New("KLSwitch: timeout must be >= 0")
	errAnalyticsNeedsSink     = errors.New("KLSwitch: WithAnalytics requires AnalyticsOptions.Sink")
)
