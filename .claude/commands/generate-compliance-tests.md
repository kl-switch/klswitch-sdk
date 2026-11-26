---
description: Generate shared compliance fixtures and tests for a requested rule behavior.
---

Read:
- docs/testing-strategy.md
- docs/rule-model.md
- spec/schemas/
- spec/compliance/v1/cases/
- spec/examples/

Generate compliance fixtures and tests for the requested rule behavior.
Prefer reusable shared fixtures (the `spec/compliance/v1/cases/` envelope) over
wrapper-specific logic, so Node and Java consume the same files.
