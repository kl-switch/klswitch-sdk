# Security policy

## Supported versions

The `0.1.x` line of every wrapper — `@klswitch/sdk-node`, `klswitch-sdk-java`,
`klswitch-sdk` (Python) and `github.com/kl-switch/klswitch-sdk/sdk-go` —
receives security fixes. All four embed the same WASM engine built from
`rust-core/`, so an engine fix ships to all of them together.

## Reporting a vulnerability

Email **dev@klswitch.com** with the affected wrapper and version, what you
observed, and a reproduction if you have one. Please do not open a public
issue for a vulnerability.

You will get an acknowledgement within three working days. If the issue is
confirmed, we will agree a disclosure date with you before publishing.

## What is in scope

- The WASM engine: rule parsing, compilation, selector matching, evaluation,
  and the host boundary described in `docs/wasm-host-contract.md`. Memory
  handling across that boundary is the sharpest edge in this repo.
- The wrappers: rules-API polling, cache file handling, the analytics queue,
  and the framework adapters.

Rules are configuration supplied by the operator, not untrusted input — a rule
that blocks the wrong route is a configuration bug. A ruleset that can read
memory outside the engine, crash the host, or escape the WASM sandbox is a
vulnerability, and so is any path where an evaluation input (a request body,
header or query value from an end user) can do the same.
