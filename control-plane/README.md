# control-plane

Real backend service for KLSwitch.

Lives **alongside** `backend/`, which holds only contract material
(OpenAPI + static mock fixtures used by SDK wrapper tests). This folder
is the implementation that operator consoles + SDK wrappers will
actually talk to in production.

## Two surfaces, one process

| Path                              | Consumer            | Status       |
|----------------------------------- |---------------------|--------------|
| `GET  /v1/ruleset?service=…&env=…` | SDK wrappers (poll) | Phase 8 MVP  |
| `GET  /api/control/orgs/:org/*`    | Operator console    | Phase 8 MVP (reads) |
| `POST /api/control/orgs/:org/analytics` | SDK wrappers   | Phase 8 MVP  |

The endpoint shapes match the frozen contracts under `../spec/schemas/`
and the MSW handlers in `../../klswitch-console/src/mocks/handlers/`
verbatim — so the console can swap its `apiBase` without UI changes and
the wrappers can swap their `rulesUrl` without engine changes.

## Phase 8 scope (this commit)

- Reads only for the operator surface — mutations land in Phase 9.
- In-memory store, no persistence; on restart everything reverts to
  seeds. Acceptable for v0 demo; SQLite + a small migration story is
  the natural follow-up.
- No auth. Both surfaces are open in dev. Wrapper bearer-token check
  + operator session cookies land in Phase 10.

## Running

```bash
cd control-plane
npm install
npm run dev      # tsx watch — restarts on src/ change

# Smoke
curl http://localhost:8787/health
curl http://localhost:8787/v1/ruleset?service=payments-api\&env=prod
curl http://localhost:8787/api/control/orgs/acme/rules/summary
```

## Pointing the console at this

In `klswitch-console`:

```bash
NEXT_PUBLIC_API_BASE=http://localhost:8787 npm run dev
```

That env var disables MSW boot and points `apiFetch` at the real
backend. Without the var, the console keeps its mock-mode behaviour.
