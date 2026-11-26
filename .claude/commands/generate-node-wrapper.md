---
description: Generate a Node.js wrapper module for KLSwitch that respects the WASM boundary.
---

Read:
- CLAUDE.md
- docs/architecture.md
- docs/sdk-wrapper-contract.md

Now generate a Node.js wrapper module for KLSwitch.
Requirements:
- wrapper owns polling, logging, analytics, and WASM loading
- do not move engine logic into wrapper
- keep API clean and minimal
