package klswitch

import _ "embed"

// embeddedWASM is the bundled engine module. Ship the SDK as a single Go binary
// import — no install-time WASM fetch, no native deps (wazero is pure Go).
//
//go:embed klswitch_core.wasm
var embeddedWASM []byte
