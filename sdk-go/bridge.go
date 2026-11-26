package klswitch

import (
	"context"
	"encoding/binary"
	"fmt"
	"sync"

	"github.com/tetratelabs/wazero"
	"github.com/tetratelabs/wazero/api"
)

// bridge is the typed wrapper over the engine's WASM exports
// (see docs/wasm-host-contract.md §8). It owns the JSON in/out memory protocol:
// allocate an input buffer, write UTF-8 bytes, call an export, read the
// length-prefixed result, free both buffers. JSON strings in/out — no parsing here.
//
// Runs on wazero (a pure-Go WASM runtime), so there are no native deps. The
// engine module has zero imports, so instantiation needs no host functions.
//
// All engine calls are serialized by `mu`: wazero allows concurrent calls into
// a single module, but the engine's handle table and per-engine state are
// `thread_local`/`RefCell` on the Rust side (WASM is single-threaded), so a
// process-wide mutex around each engine round-trip is required.
type bridge struct {
	mu sync.Mutex
	rt wazero.Runtime
	// All `ks_*` exports kept as typed [api.Function] handles.
	mod          api.Module
	mem          api.Memory
	createEngine api.Function
	destroyEngine api.Function
	loadRules    api.Function
	evaluate     api.Function
	getStatus    api.Function
	alloc        api.Function
	free         api.Function
	freeResult   api.Function
}

const lenPrefix = 4

// newBridge instantiates the embedded engine module and resolves every export.
// If `customWASM` is non-empty it is used instead of the bundled module.
func newBridge(ctx context.Context, customWASM []byte) (*bridge, error) {
	wasmBytes := embeddedWASM
	if len(customWASM) > 0 {
		wasmBytes = customWASM
	}

	rt := wazero.NewRuntime(ctx)
	mod, err := rt.Instantiate(ctx, wasmBytes)
	if err != nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("instantiate engine module: %w", err)
	}

	b := &bridge{rt: rt, mod: mod, mem: mod.Memory()}
	resolvers := []struct {
		name string
		dst  *api.Function
	}{
		{"ks_create_engine", &b.createEngine},
		{"ks_destroy_engine", &b.destroyEngine},
		{"ks_load_rules", &b.loadRules},
		{"ks_evaluate", &b.evaluate},
		{"ks_get_status", &b.getStatus},
		{"ks_alloc", &b.alloc},
		{"ks_free", &b.free},
		{"ks_free_result", &b.freeResult},
	}
	for _, r := range resolvers {
		fn := mod.ExportedFunction(r.name)
		if fn == nil {
			_ = rt.Close(ctx)
			return nil, fmt.Errorf("engine module is missing export %q (rebuild the WASM?)", r.name)
		}
		*r.dst = fn
	}
	if b.mem == nil {
		_ = rt.Close(ctx)
		return nil, fmt.Errorf("engine module is missing linear memory")
	}
	return b, nil
}

// CloseRuntime tears down the wazero runtime and every engine instance it owns.
func (b *bridge) CloseRuntime(ctx context.Context) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.rt.Close(ctx)
}

func (b *bridge) CreateEngine(ctx context.Context) (uint32, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	res, err := b.createEngine.Call(ctx)
	if err != nil {
		return 0, fmt.Errorf("ks_create_engine: %w", err)
	}
	return uint32(res[0]), nil
}

func (b *bridge) DestroyEngine(ctx context.Context, handle uint32) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if _, err := b.destroyEngine.Call(ctx, uint64(handle)); err != nil {
		return fmt.Errorf("ks_destroy_engine: %w", err)
	}
	return nil
}

func (b *bridge) LoadRules(ctx context.Context, handle uint32, rulesJSON string) (string, error) {
	return b.callJSON(ctx, b.loadRules, handle, rulesJSON)
}

func (b *bridge) Evaluate(ctx context.Context, handle uint32, inputJSON string) (string, error) {
	return b.callJSON(ctx, b.evaluate, handle, inputJSON)
}

func (b *bridge) GetStatus(ctx context.Context, handle uint32) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	res, err := b.getStatus.Call(ctx, uint64(handle))
	if err != nil {
		return "", fmt.Errorf("ks_get_status: %w", err)
	}
	return b.readAndFree(ctx, uint32(res[0]))
}

// callJSON writes `json` to an input buffer, invokes `fn(handle, ptr, len)`,
// reads + frees the length-prefixed result, then frees the input buffer.
func (b *bridge) callJSON(ctx context.Context, fn api.Function, handle uint32, json string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	data := []byte(json)
	dataLen := uint32(len(data))

	allocRes, err := b.alloc.Call(ctx, uint64(dataLen))
	if err != nil {
		return "", fmt.Errorf("ks_alloc(%d): %w", dataLen, err)
	}
	inputPtr := uint32(allocRes[0])
	if inputPtr == 0 && dataLen > 0 {
		return "", fmt.Errorf("ks_alloc(%d) returned null", dataLen)
	}

	// Ensure the input buffer is freed even on a panic mid-call.
	defer func() {
		_, _ = b.free.Call(ctx, uint64(inputPtr), uint64(dataLen))
	}()

	if dataLen > 0 && !b.mem.Write(inputPtr, data) {
		return "", fmt.Errorf("memory write of %d bytes at %d failed", dataLen, inputPtr)
	}

	callRes, err := fn.Call(ctx, uint64(handle), uint64(inputPtr), uint64(dataLen))
	if err != nil {
		return "", fmt.Errorf("engine call: %w", err)
	}
	return b.readAndFree(ctx, uint32(callRes[0]))
}

// readAndFree decodes a length-prefixed result buffer (`[u32 LE len][json]`) and frees it.
//
// Caller MUST already hold `b.mu`.
func (b *bridge) readAndFree(ctx context.Context, ptr uint32) (string, error) {
	if ptr == 0 {
		return "", fmt.Errorf("engine returned a null result buffer")
	}
	prefix, ok := b.mem.Read(ptr, lenPrefix)
	if !ok {
		return "", fmt.Errorf("memory read of result length prefix at %d failed", ptr)
	}
	length := binary.LittleEndian.Uint32(prefix)
	body, ok := b.mem.Read(ptr+lenPrefix, length)
	if !ok {
		return "", fmt.Errorf("memory read of result body (len=%d) at %d failed", length, ptr+lenPrefix)
	}
	// Copy to a new string so the engine memory can be safely freed below
	// (wazero's `Read` returns a slice aliased to the underlying memory).
	out := string(body)
	if _, err := b.freeResult.Call(ctx, uint64(ptr)); err != nil {
		return "", fmt.Errorf("ks_free_result: %w", err)
	}
	return out, nil
}
