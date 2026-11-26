module github.com/kl-switch/klswitch-sdk/examples-go

go 1.22.0

toolchain go1.22.3

require github.com/kl-switch/klswitch-sdk/sdk-go v0.0.0

require github.com/tetratelabs/wazero v1.9.0 // indirect

// The example consumes the local SDK directly so changes to sdk-go are picked
// up without a release cycle. Drop the replace once sdk-go is published.
replace github.com/kl-switch/klswitch-sdk/sdk-go => ../sdk-go
