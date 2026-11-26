#!/usr/bin/env bash
#
# The frozen architecture boundary, as a gate.
#
# The WASM core is rule logic only: parsing, validation, compilation, selector
# indexing, evaluation, status. Host concerns — network, timers, filesystem,
# env, threads — live in the wrappers. docs/architecture.md and CLAUDE.md state
# the rule; this script is what stops it eroding.
#
# Targets clock and IO access specifically (SystemTime/Instant), not benign
# Duration arithmetic: the engine takes `now` as a trusted input from the host
# rather than reading a clock itself, which is what makes evaluation pure and
# the scheduled-rule fixtures replayable.
set -euo pipefail

cd "$(dirname "$0")/.."

forbidden='reqwest|hyper|tokio|std::net|std::fs|std::env|std::thread|SystemTime|Instant|TcpStream|TcpListener'

if grep -rnE "$forbidden" rust-core/src/; then
  echo >&2
  echo "BOUNDARY VIOLATION: rust-core/src contains host/runtime code (matches above)." >&2
  echo "Move it into the wrappers — see CLAUDE.md, 'Architecture boundary (frozen)'." >&2
  exit 1
fi

echo "rust-core/src is clean of network / timer / fs / env / thread code."
