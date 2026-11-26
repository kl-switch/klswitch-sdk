# klswitch-examples

Runnable Express and Fastify servers that exercise `@klswitch/sdk-node` against
real HTTP traffic. Useful for poking the engine end to end and for adding your own
APIs + rules.

## Setup

The example depends on the **local** SDK via `file:../sdk-node`, so build the SDK
first (it bundles the `.wasm`), then install here:

```bash
# from the repo root
( cd sdk-node && npm install && npm run build )   # produce sdk-node/dist + wasm
( cd examples && npm install )
```

Re-run `npm run build` in `sdk-node` after changing the SDK; the `file:` link picks
it up.

## Run

```bash
cd examples
npm run start:express   # http://localhost:3000
npm run start:fastify   # http://localhost:3001
```

## Try it (curl)

Set the port you started on (e.g. `PORT=4790`). All examples use `localhost:4790`.

```bash
B=localhost:4790
J='content-type: application/json'

# ── payments: priority + multiple matches ──
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":100}'                         # allowed, matched []
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":70000}'                       # 429 block_large_payment (gt)
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":10,"country":"KP"}'           # 451 block_restricted_region (in)
curl -sX POST $B/v1/payments/abc -H "$J" -d '{"amount":20000,"first_time":true}'     # 200, matched flag + notify (gte/lte + eq, priority order)

# ── auth: gte, nested any, disabled rule ──
curl -sX POST $B/v1/auth/login -H "$J" -d '{"failed_attempts":6}'                    # 429 brute-force (gte)
curl -sX POST $B/v1/auth/login -H "$J" -d '{"country":"IN","meta":{}}'               # 200 — legacy geo rule is DISABLED
curl -sX POST $B/v1/auth/login -H "$J" -d '{"country":"IN"}'                         # (ip_reputation set server-side? see note)

# ── users: any + exists ──
curl -s "$B/v1/users/42?role=admin"                                                  # flag_admin_access (eq)
curl -s "$B/v1/users/42" -H 'x-impersonate: ops'                                     # flag_admin_access (exists)
curl -s "$B/v1/users/42"                                                             # no match

# ── products: lt ──
curl -sX PUT $B/v1/products/p1 -H "$J" -d '{"price":-5}'                             # 400 block_negative_price (lt 0)
curl -sX PUT $B/v1/products/p1 -H "$J" -d '{"stock":3}'                              # flag_low_inventory (lt)

# ── orders: not_in, gt ──
curl -sX POST $B/v1/orders -H "$J" -d '{"currency":"JPY"}'                           # 400 unsupported_currency (not_in)
curl -sX POST $B/v1/orders -H "$J" -d '{"currency":"USD","item_count":500}'         # 413 oversized_order (gt)

# ── messages: contains / not_contains ──
curl -sX POST $B/v1/messages -H "$J" -d '{"text":"buy spam now"}'                   # 403 spam (contains)
curl -sX POST $B/v1/messages -H "$J" -d '{"text":"hello world"}'                    # flag_missing_disclaimer (not_contains)
curl -sX POST $B/v1/messages -H "$J" -d '{"text":"hello, see disclaimer below"}'   # no match

# ── search: contains on query + not_exists ──
curl -s "$B/v1/search?q=DROP%20TABLE%20users"                                        # 400 injection (contains)
curl -s "$B/v1/search"                                                               # flag_empty_search (not_exists)

# ── sessions: not_exists on a header (auth required) ──
curl -sX DELETE $B/v1/sessions/s1                                                    # 401 auth_required (no header)
curl -sX DELETE $B/v1/sessions/s1 -H 'authorization: Bearer x'                       # 200

# ── admin: not(eq) — a MISSING header is also blocked (neq would let it through) ──
curl -s "$B/v1/admin/reports"                                                        # 403 (header missing)
curl -s "$B/v1/admin/reports" -H 'x-role: user'                                      # 403 (not admin)
curl -s "$B/v1/admin/reports" -H 'x-role: admin'                                     # 200

# ── feature: nested all/any/not + dotted path + neq ──
curl -sX POST $B/v1/feature/evaluate -H "$J" -d '{"user":{"tier":"premium"},"region":"us","env":"prod"}'  # allow
curl -sX POST $B/v1/feature/evaluate -H "$J" -d '{"user":{"tier":"premium","blocked":true},"region":"us"}' # no match (not blocked=true fails)

# engine status
curl -s $B/health
```

The Fastify server responds identically — same rules, same WASM engine.

> Note on `flag_suspicious_login`: it requires `meta.ip_reputation == "suspicious"`,
> which the demo servers don't set from the request (`meta` is server-provided
> context). Add it in the adapter mapping to exercise that rule.

## How it's wired

- The demo ruleset is in `src/rules.ts` (paths are the templated routes).
- `klswitchMiddleware` (Express) / `klswitchHook` (Fastify) translate the
  request into the engine's evaluation input and attach the `EvaluationResult`.
- The block/allow **policy** lives in each server's `onResult` — the adapters
  themselves never interpret actions.

Add endpoints and rules freely; this server is meant to grow.
