# klswitch-examples-go

A minimal Go `net/http` server that exercises `sdk-go` over real HTTP — the Go
counterpart to the Node `examples/` and Java `examples-java/` demos.

The example wires a small **`Guard` middleware** around each route (a preview of
the packaged middleware that lands in Phase G3 of the SDK) and reuses the same
**three demo rules** as the Node/Java servers, so the curl invocations below
produce identical responses across all three languages.

## Setup

The example consumes the local SDK directly via a `replace` directive in
`go.mod`, so changes in `sdk-go/` are picked up without a release cycle.

```bash
cd examples-go
go mod tidy
```

## Integration tests (the main validation)

```bash
go test ./...
```

Tests boot an in-process server via `httptest.NewServer(buildMux(client))` and
hit each route over real HTTP. They cover the three demo rules plus the
`/health` endpoint — no Jetty/Express equivalent boot needed.

## Run it as a real server

```bash
go run .                # listens on :8087
PORT=9000 go run .      # custom port
```

```bash
B=localhost:8087
J='content-type: application/json'

# Small payment: no rule matches → 200 OK, matched: []
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":100}'

# Large payment: block_large_payment (gt 50000) → 429
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":70000}'

# Restricted region: block_restricted_region (in ["KP","IR"]) → 451
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":10,"country":"KP"}'

# Admin via query param: flag_admin_access (non-blocking) → 200 with matched flag
curl -s   "$B/v1/users/42?role=admin"

# Admin via header: same rule fires through the `any` branch
curl -s   $B/v1/users/42 -H 'X-Impersonate: ops-user'

# Engine status
curl -s   $B/health
```

## What this demo proves

- The Go SDK works end-to-end against real HTTP semantics (not just compliance fixtures).
- A user-written middleware over `sdk-go` is short and idiomatic (~80 lines in `middleware.go`).
- Behaviour is **identical** to the Node/Java demos for the same three rules — same
  status codes, same `ruleId`, same `matched` shape.
- The integration tests run in-process via `httptest` (no port binding, no flakiness).

## Layout

| File | Purpose |
|------|---------|
| `main.go` | Entry point: `Init`, build mux, serve, graceful shutdown |
| `rules.go` | The three demo rules (mirrors `examples-java/.../demo-rules.json`) |
| `middleware.go` | The `Guard` middleware + request→`EvaluationInput` mapping |
| `services.go` | Dummy resource handlers (`/v1/payments/:id`, `/v1/users/:id`, `/health`) |
| `server_test.go` | `httptest`-based end-to-end checks for every demo rule |
