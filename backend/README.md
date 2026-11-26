# backend

Rules API materials. **v1 mocks the rules API** — there is no real backend
provider yet (Phase 14 builds the mock; the real backend comes later).

This folder holds rules-API contract material only, never engine logic:

- `openapi/rules-api.yaml` — the rules-fetch endpoint contract (`HEAD` / `GET /v1/ruleset` — v1.1 adds ETag + Cache-Control)
- `examples/mock-rules-response.json` — a valid ruleset response (`rules_version: 1`)
- `examples/mock-rules-response-v2.json` — a changed ruleset (`rules_version: 2`),
  for validating that a refresh picks up a new version
- `examples/mock-rules-invalid.json` — a non-ruleset payload, for validating that
  a bad response is rejected and the last-known-good ruleset is kept

The response body **is** the ruleset — the exact shape the wrapper passes verbatim
into the WASM `load_rules` boundary
([`spec/schemas/ruleset.schema.json`](../spec/schemas/ruleset.schema.json)). There
is no outer envelope. Wrappers keep last-known-good on any non-2xx or unparseable
response.

**v1.1 server-driven polling.** Every response (HEAD or GET) carries:

  - `ETag: "<strong-tag>"` — content fingerprint of the ruleset bytes
  - `Cache-Control: max-age=<seconds>` — wrapper schedules its next poll
    from this value (clamped to a sane min/max bound)

Wrappers `HEAD` first to detect changes cheaply, then `GET` only when the
ETag flipped, sending `If-None-Match: "<lastEtag>"` for race safety
(server replies `304 Not Modified` if unchanged). Wrappers talking to a
backend that omits these headers degrade gracefully to plain GET on the
configured `refreshInterval`.

The mock exists so wrapper polling/refresh can be validated without a live
service. Both wrappers' test suites serve these fixtures over a real local HTTP
server (Node `http`, Java `com.sun.net.httpserver`) and exercise the default HTTP
fetcher end-to-end. A future real backend must serve the same response shape so it
can replace the mock without changing the SDK wrapper contract. See
[`../docs/implementation-plan.md`](../docs/implementation-plan.md) Phase 14.
