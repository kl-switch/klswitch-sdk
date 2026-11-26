# Testing Strategy

## Purpose

This document defines how KLSwitch should be tested across core engine and wrappers.

---

## 1. Compliance tests

Shared fixtures must validate:
- selector lookup correctness
- nested condition logic
- operator behavior
- missing field behavior
- enabled=false behavior
- matched rule ordering

These tests should be reusable across wrappers.

---

## 2. WASM bridge tests

Must validate:
- alloc/free correctness
- JSON roundtrip correctness
- repeated evaluate safety
- repeated load_rules safety
- output buffer freeing
- no obvious memory lifecycle mistakes

---

## 3. Replacement tests

Must validate:
- valid refresh replaces active rules
- invalid refresh does not replace active rules
- concurrent evaluations remain safe during refresh boundaries

---

## 4. Wrapper tests

Each wrapper must test:
- init flow
- refresh flow
- evaluate flow
- status reporting
- error surfacing