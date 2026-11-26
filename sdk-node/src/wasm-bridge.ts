import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import type { EngineStatus, EvaluationResult, LoadRulesResult } from "./types.js";

/** The `ks_*` exports of the engine module (see `docs/wasm-host-contract.md` §8). */
interface KsExports {
  memory: WebAssembly.Memory;
  ks_create_engine(): number;
  ks_destroy_engine(handle: number): void;
  ks_load_rules(handle: number, ptr: number, len: number): number;
  ks_evaluate(handle: number, ptr: number, len: number): number;
  ks_get_status(handle: number): number;
  ks_alloc(size: number): number;
  ks_free(ptr: number, size: number): void;
  ks_free_result(ptr: number): void;
}

const DEFAULT_WASM_PATH = fileURLToPath(new URL("../wasm/klswitch_core.wasm", import.meta.url));
const RESULT_LEN_PREFIX = 4;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/**
 * Thin, typed wrapper over the engine's WASM exports. Owns the JSON in/out
 * memory protocol: allocate an input buffer, write UTF-8 bytes, call an export,
 * read the length-prefixed result, and free both buffers.
 *
 * Note: `ks_alloc` (and the export itself, which allocates the result) can grow
 * — and therefore detach — the WASM memory, so every typed-array view is created
 * fresh from `memory.buffer` immediately before use. Returned pointers are
 * coerced to unsigned (`>>> 0`) because WASM i32 results surface as signed in JS.
 */
export class WasmBridge {
  private constructor(private readonly exports: KsExports) {}

  /** Load and instantiate the engine module (zero imports — no importObject needed). */
  static async load(wasmPath: string = DEFAULT_WASM_PATH): Promise<WasmBridge> {
    const bytes = readFileSync(wasmPath);
    const { instance } = await WebAssembly.instantiate(bytes, {});
    return new WasmBridge(instance.exports as unknown as KsExports);
  }

  createEngine(): number {
    return this.exports.ks_create_engine() >>> 0;
  }

  destroyEngine(handle: number): void {
    this.exports.ks_destroy_engine(handle);
  }

  loadRules(handle: number, rulesJson: string): LoadRulesResult {
    return this.callJson((ptr, len) => this.exports.ks_load_rules(handle, ptr, len), rulesJson);
  }

  evaluate(handle: number, inputJson: string): EvaluationResult {
    return this.callJson((ptr, len) => this.exports.ks_evaluate(handle, ptr, len), inputJson);
  }

  getStatus(handle: number): EngineStatus {
    const resultPtr = this.exports.ks_get_status(handle) >>> 0;
    return this.readAndFree(resultPtr);
  }

  /** Write `json` to an input buffer, call `invoke`, then read+free the result and free the input. */
  private callJson<T>(invoke: (ptr: number, len: number) => number, json: string): T {
    const bytes = encoder.encode(json);
    const inputPtr = this.exports.ks_alloc(bytes.length) >>> 0;
    try {
      // View created AFTER alloc — alloc may have grown/detached memory.
      new Uint8Array(this.exports.memory.buffer, inputPtr, bytes.length).set(bytes);
      const resultPtr = invoke(inputPtr, bytes.length) >>> 0;
      return this.readAndFree(resultPtr);
    } finally {
      this.exports.ks_free(inputPtr, bytes.length);
    }
  }

  /** Decode a length-prefixed result buffer (`[u32 LE len][json]`) and free it. */
  private readAndFree<T>(resultPtr: number): T {
    try {
      const view = new DataView(this.exports.memory.buffer);
      const len = view.getUint32(resultPtr, true);
      const bytes = new Uint8Array(this.exports.memory.buffer, resultPtr + RESULT_LEN_PREFIX, len);
      // `decode` copies into a JS string, so it is safe to free afterward.
      return JSON.parse(decoder.decode(bytes)) as T;
    } finally {
      this.exports.ks_free_result(resultPtr);
    }
  }
}
