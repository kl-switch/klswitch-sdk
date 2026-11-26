---
name: generate-wrapper-method
description: Generates language-specific KLSwitch SDK wrapper methods and adapters per docs/sdk-wrapper-contract.md. Use for Node or Java SDK code when the user asks for init, evaluate, refresh, getStatus, middleware, or WASM loading — keeping engine logic inside WASM.
---

# Generate Wrapper Method

## Instructions

1. **Division of labor**:
   - **Wrapper**: WASM loading, engine lifecycle, rules polling/refresh, logging, analytics batching/sending, public SDK API, framework adapters (Express, Fastify, Spring, etc.).
   - **WASM**: Rule validation, compilation, indexing, atomic replacement, evaluation, status computation — **no** network, logging, or analytics.

2. **Public API parity** (`docs/sdk-wrapper-contract.md`): Expose equivalent capabilities — typically `init(config)`, `evaluate(input)`, `refresh()`, `getStatus()`, `close()` — using idiomatic names for the language.

3. **Init**: Support inline rules and/or remote rules URL, optional refresh interval, optional API headers, timeouts. If initial load fails and no fallback rules, fail clearly by default.

4. **Refresh**: Polling runs in the wrapper (disabled unless `refresh_interval` is set); wrapper passes full ruleset JSON into WASM `load_rules`. Log success/failure in the wrapper. On failure, **keep last known good** rules. Refresh must **not** block the evaluate path.

5. **Evaluate**: Wrapper serializes stable evaluation input (see `spec/schemas/evaluation-input.schema.json`), calls WASM, deserializes result; optional async analytics **after** returning (non-blocking).

6. **Logging / analytics**: Only in wrapper; recommended events per `docs/sdk-wrapper-contract.md` (load, refresh, wasm errors, summary stats — not every evaluation by default).

7. **Framework adapters**: Translate framework requests into the shared evaluation input model (`selector` + `facts`); no engine semantics in the adapter beyond mapping. v1 adapters: Express/Fastify (Node), Spring interceptor/servlet filter (Java).

8. **Bridge**: Use explicit alloc/write/call/read/free per `docs/wasm-host-contract.md`; never reimplement rule matching or condition evaluation in the wrapper.
