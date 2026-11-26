# Rule Model

## Purpose

This document defines the stable v1 rule model.

KLSwitch is generic by design, but v1 officially supports API selectors.

---

## 1. RuleSetPayload

Fields:
- schema_version
- rules_version
- rules

---

## 2. Rule

Fields:
- id
- name
- selector
- enabled
- priority
- condition
- action
- tags
- metadata
- effective (optional; see §7.5)

---

## 3. Selector

Generic structure:
- kind
- keys

V1 supported kind:
- api

V1 required keys for api:
- method
- path

---

## 4. ConditionNode

Supported forms:
- all
- any
- not
- leaf

Leaf structure:
- field
- operator
- value

---

## 5. Operators

V1 operators:
- eq
- neq
- gt
- gte
- lt
- lte
- contains
- not_contains
- in
- not_in
- exists
- not_exists

### 5.1 Operator type semantics (frozen)

The engine performs **no implicit type coercion**. Behavior is defined per
operator so it is identical across every wrapper language.

| Operator | Field type expected | `value` type | Behavior |
| --- | --- | --- | --- |
| `eq` / `neq` | any JSON scalar | matching scalar | JSON value equality. JSON has a single number type, so `5` **equals** `5.0` (compared by numeric value); but a number never equals a string (`5` ≠ `"5"`) and types must otherwise match. `eq` is `false` on type mismatch; `neq` is `true` on type mismatch (field is present but differs). Numeric comparison uses IEEE-754 `f64`, so integers beyond 2^53 may lose precision (consistent across wrappers). |
| `gt` / `gte` / `lt` / `lte` | number | number | Numeric comparison. If the field value **or** `value` is not a number, the leaf is **`false`** (no coercion, no error). |
| `contains` / `not_contains` | string **or** array | scalar | String field → substring test (`value` must be a string). Array field → element membership using `eq` semantics. Any other field type → `contains` is `false`, `not_contains` is `true`. |
| `in` / `not_in` | any JSON scalar | array | Membership of the field value in the `value` array, compared with `eq` semantics. `value` **must** be an array — a non-array `value` is a compile-time invalid rule (see §7.3). |
| `exists` / `not_exists` | — | omitted | Presence test on the resolved field path. See §7.2. |

`value` is required for every operator **except** `exists` / `not_exists`,
which take no `value` (enforced by `ruleset.schema.json`).

---

## 6. Action

Action is a structured object:
- type (required)
- payload (optional)

The engine returns the matched action verbatim; it never executes host-side
business logic. `type` is an open string in v1.

---

## 7. Evaluation behavior

### 7.1 Order of operations

1. Disabled rules (`enabled: false`) are skipped **before** selector or
   condition evaluation and counted in the result `skipped` total.
2. The selector shortlists candidate rules (see §8).
3. A candidate whose **effective window** excludes the input's `now` is skipped
   **before** condition evaluation and counted in `skipped` — exactly like a
   disabled rule (see §7.5). Rules without an `effective` window skip this check.
4. Condition trees are evaluated **only** on shortlisted, active candidates.
5. Every matched rule is returned (no early exit / first-match-wins).

### 7.2 Missing fields and field-path resolution

- A field path (e.g. `body.amount`, `headers.x-client-id`,
  `body.user.id`) resolves by walking nested objects from the `facts` root.
- A **missing field** is any path that does not resolve, **including** a path
  whose intermediate parent object is absent (`body.user.id` when `body.user`
  is missing).
- For every operator **except** `exists` / `not_exists`, a missing field makes
  the leaf evaluate to **`false`** — uniformly, including the negative
  operators (`neq`, `not_contains`, `not_in`). This prevents a negative
  operator from accidentally matching absent data.
- `exists` is `true` iff the field path resolves to a present value (an explicit
  JSON `null` counts as present). `not_exists` is the inverse and is `true` for
  a missing field.

### 7.3 Type mismatch (field present, wrong type)

- Per §5.1, a present field of the wrong type yields a **`false`** leaf for the
  comparison operators (it never raises an error at evaluate time).
- An invalid operator, or an `in` / `not_in` whose `value` is not an array, is a
  **compile-time** error during `load_rules` — the rule is skipped and reported
  in the load result, not handled per-evaluation.

### 7.4 Result ordering and tie-break (frozen)

- Matches are returned sorted by **`priority` descending**.
- **Ties** (equal `priority`) are broken by **ascending declaration order** —
  the order rules appear in the `rules` array of the ruleset payload.
- Implementations MUST use a **stable sort** keyed on priority so this holds
  across languages. Result ordering is deterministic for any given ruleset +
  input.

### 7.5 Effective window (optional scheduling, frozen)

A rule may carry an optional `effective` object that bounds **when** it is
active. It is the scheduled complement to the manual `enabled` kill switch.

```
"effective": { "from": <epoch-millis>, "to": <epoch-millis> }
```

- **Units:** both bounds are **epoch milliseconds** (integers), matching the
  `now` supplied on the evaluation input. Every wrapper sends `now` in millis.
- **Both bounds optional.** `from` only ⇒ active from then on; `to` only ⇒
  active until then; neither (or `effective` absent / `{}`) ⇒ **always active**
  (the field normalizes away).
- **Bounds are inclusive:** the rule is active when `from <= now <= to`.
- **The engine has no clock.** Wall-clock time is passed in as `now` on the
  evaluation input (a trusted timestamp the wrapper injects — the engine never
  reads a system clock, preserving pure/deterministic evaluation).
- **Missing `now` (fail-safe):** if a windowed rule is evaluated against an input
  with no `now`, the rule is **inactive** (skipped) — a scheduled rule never
  fires without a trusted clock. Rules with no window are unaffected by `now`.
- **Counting:** a candidate outside its window (or windowed with no `now`) is
  counted in `skipped`, not `evaluated` — the same treatment as `enabled: false`.
- **Validation (`load_rules`):** each bound must be an integer; `from > to` is a
  **compile-time error** — that rule is skipped and reported (partial-load
  semantics, §7.3), the rest of the ruleset still loads.
- **Determinism:** for a fixed ruleset **and** a fixed `now`, evaluation is
  identical across all wrappers (pinned by the `eval-effective-*` compliance
  fixtures, which carry an explicit `now`).

#### 7.5.1 Rollout order (important — version skew fails *open*)

`effective` arrived in **schema version 1.1**. The load path ignores unknown
rule-level keys (only condition leaves and `effective` itself reject unknown
keys), so an engine older than 1.1 **silently ignores the window and treats the
rule as always active**:

- a rule scheduled to start next month fires **immediately**, and
- a rule whose `to` has passed **keeps firing**.

This fails *open* (rule active), not closed, so it is the dangerous direction.
The published `schema_version` is informational — no engine rejects on it — so
nothing detects the mismatch automatically.

**Therefore: upgrade the fleet to an SDK carrying the 1.1 engine *before*
publishing any rule that uses `effective`.** Check the instance list for the
(service, env) and confirm every instance reports a window-capable SDK version.
A rule with no `effective` is unaffected and safe to publish at any time.

---

## 8. Selector matching (API: exact and pattern)

- The engine matches `selector.kind = "api"` on `keys.method` and `keys.path`.
  `keys.method` is always an **exact** comparison.
- The wrapper is responsible for **normalization** before calling `evaluate`:
  uppercase the HTTP method and use the **templated route** path
  (`/v1/payments/:id`), not a concrete instance path (`/v1/payments/123`).

### 8.1 Path patterns (frozen, schema 1.2)

`keys.path` is an exact string unless it contains a `*` segment.

| Token | Matches                                                          |
| ----- | ---------------------------------------------------------------- |
| `*`   | exactly one path segment                                          |
| `**`  | zero or more trailing segments — **only valid as the last segment** |

- Segments are split on `/`. A segment containing `*` must be **exactly** `*` or
  exactly `**`; partial-segment globs (`/v1/or*ders`) are not supported and are
  rejected at load time.
- `/v1/**` matches `/v1` itself as well as `/v1/a` and `/v1/a/b`. `**` matching
  *zero* segments is deliberate — "this route and everything under it" is the
  common intent, and forcing a second exact rule for the bare prefix is a trap.
- Because paths arrive **templated**, `*` matches a parameter segment as ordinary
  text: `/v1/orders/*` matches `/v1/orders/:id` (Express) and `/v1/orders/{id}`
  (JAX-RS, FastAPI, chi) alike. This is precisely why `*` is the pattern
  character — `:` and `{}` are already occupied by framework route templating,
  so either would collide with a real path in at least one supported language.
- A path with no `*` is an exact path and takes the exact-match fast path.

### 8.2 Ordering is unchanged (frozen)

A pattern rule and an exact rule may both match one request. When they do,
**both are returned**, ordered by §7.4 — priority descending, ties broken by
declaration order. Specificity does **not** participate in ordering. The model
returns *all* matches rather than selecting one, so priority already expresses
precedence; adding a specificity tier would silently re-order results for
rulesets that are valid today.

### 8.3 Indexing

The engine builds a two-tier selector index at load time:

- exact rules → hash bucket keyed `(method, path)` — unchanged from schema 1.1
- pattern rules → a per-method list, scanned linearly

A ruleset with no patterns therefore costs exactly what it did before. Patterns
are compiled to segment vectors at load time and are **never re-parsed during
evaluation**, which runs inside the caller's request path.

The two candidate sets are merged in declaration order, so the candidate list
never exposes which tier a rule came from. §7.4 itself is enforced by the result
sort, not by this merge — a pattern rule and an exact rule of equal priority tie
on declaration order regardless of how candidates were gathered.

### 8.4 Validation and compatibility

- A malformed pattern (`**` not final, `***`, a partial-segment glob) fails that
  rule at compile time with `INVALID_SELECTOR_PATTERN`. As with any rule-level
  compile error the rule is skipped and the rest of the ruleset still activates.
- Patterns arrived in **schema version 1.2**. Mirroring §7.5, an engine older
  than 1.2 treats `/v1/*` as a literal path and simply never matches it —
  fail-safe, but silent. Publish patterns only once the deployed SDKs report
  schema 1.2 in their engine status.

---

## 9. Limits (frozen, PRD §22.2)

The engine enforces these caps during `load_rules`:

- **Max 1000 rules per ruleset.** A payload with more rules is rejected
  wholesale — `load_rules` returns `ok: false` with a single `RULE_LIMIT_EXCEEDED`
  error and the previous active ruleset is preserved (no partial activation).
- **Max 50 conditions (leaf comparisons) per rule.** A rule whose condition tree
  has more than 50 leaves is skipped individually (counted in
  `skipped_rule_count`, reported with `CONDITION_LIMIT_EXCEEDED`, `field:
  "condition"`). Branch nodes (`all` / `any` / `not`) are structure and are **not**
  counted.

Maximum JSON **payload byte size** is a wrapper-side concern (configurable per
SDK), not enforced by the engine.