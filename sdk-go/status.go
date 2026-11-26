package klswitch

// Status is the combined engine + wrapper status surfaced by [Client.GetStatus].
// `EngineStatus` is embedded so its fields are accessible directly
// (e.g. `s.Initialized`, `s.RuleCount`); the wrapper-owned fields cover the
// host-side runtime view (poll state, last refresh outcome) and are NOT visible
// to the engine.
type Status struct {
	EngineStatus

	// Ready is true when the wrapper has a live engine handle and bridge.
	// Distinct from `Initialized` — a client whose `Close` ran reports
	// Ready=false even though the engine reported Initialized=true earlier.
	Ready bool `json:"ready"`

	// Polling is true while the recurring polling loop is running.
	Polling bool `json:"polling"`

	// LastRefreshAt is the epoch-ms timestamp of the last refresh attempt
	// (success or failure), or nil if none has been attempted.
	LastRefreshAt *int64 `json:"last_refresh_at,omitempty"`

	// LastRefreshOk is the outcome of the last refresh attempt, or nil if
	// none has been attempted.
	LastRefreshOk *bool `json:"last_refresh_ok,omitempty"`

	// LastRefreshError is the error message from the most recent failed
	// refresh, or nil if the last refresh succeeded / none has happened.
	// (Addresses the Phase 1 review gap: Node/Java today only surface the
	// boolean LastRefreshOk; the actual reason for failure was lost.)
	LastRefreshError *string `json:"last_refresh_error,omitempty"`
}
