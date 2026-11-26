# KLSwitch Architecture

## Overview

KLSwitch is split into two strict layers:

1. WASM core
2. SDK wrapper

This separation is the central architectural rule of the project.

---

## 1. WASM Core

The WASM core is implemented in Rust and compiled to WebAssembly.

It is responsible for:

- parsing rules
- validating rules
- compiling rules into runtime structures
- building selector indexes
- storing the active compiled ruleset in memory
- atomically replacing rulesets
- evaluating facts against active rules
- exposing engine status

The WASM core is stateful. It owns the currently active compiled rules.

---

## 2. SDK Wrappers

SDK wrappers are language-specific integration layers.

They are responsible for:

- loading the WASM artifact
- creating and destroying engine instances
- polling a remote rules API
- passing rules JSON into WASM
- passing evaluation JSON into WASM
- logging
- analytics batching and sending
- public SDK API design
- framework integration
- lifecycle management

v1 wrappers (all four shipped):
- Node.js / TypeScript wrapper (Chicory-free V8/Node runtime; Express + Fastify adapters)
- Java / Kotlin wrapper (Chicory pure-JVM WASM runtime; JAX-RS `ContainerRequestFilter`)
- Go wrapper (wazero pure-Go WASM runtime; `net/http` middleware)
- Python wrapper (wasmtime-py WASM runtime; Flask + FastAPI adapters)

All four wrappers replay the same `spec/compliance/v1/cases/*.json` fixtures
byte-identically against the shared WASM engine — that's the cross-language
parity gate.

---

## 3. Boundary Rules

### Must be inside WASM
- rule parsing
- rule validation
- condition compilation
- selector indexing
- active compiled rules
- atomic rules replacement
- evaluation logic
- engine status

### Must be outside WASM
- HTTP
- polling
- timers
- logging
- analytics sending
- framework-specific integrations
- host runtime configuration

---

## 4. Rule Model

Every rule has three important parts:

1. selector
2. condition
3. action

### Selector
Used to shortlist candidate rules.

The model is generic, but v1 supports only:
- selector.kind = "api"
- selector.keys.method
- selector.keys.path

### Condition
Used to evaluate facts on candidate rules.

Condition trees support:
- all
- any
- not
- leaf comparisons

### Action
Structured return object for matched rules.

---

## 5. Evaluation Flow

1. Wrapper builds evaluation input
2. Wrapper calls WASM evaluate
3. WASM uses selector to shortlist candidate rules
4. WASM evaluates conditions against facts
5. WASM returns all matched rules in priority-descending order
6. Wrapper uses result for application-specific handling

---

## 6. Rule Refresh Flow

1. Wrapper polls rules API
2. Wrapper receives full ruleset JSON
3. Wrapper passes ruleset JSON to WASM load_rules
4. WASM parses, validates, compiles, and indexes
5. If successful, WASM atomically replaces active rules
6. If not successful, previous active rules remain in use

---

## 7. Atomic Replacement Rule

A request must see either:
- the previous valid ruleset
- or the new valid ruleset

A request must never see:
- partially updated rules
- partially compiled rules
- mixed old/new runtime state