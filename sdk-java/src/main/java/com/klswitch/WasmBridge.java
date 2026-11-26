package com.klswitch;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin, typed wrapper over the engine's WASM exports (see
 * docs/wasm-host-contract.md §8). Owns the JSON in/out memory protocol: allocate
 * an input buffer, write UTF-8 bytes, call an export, read the length-prefixed
 * result, and free both buffers. JSON strings in/out — no parsing here.
 *
 * Runs on Chicory (a pure-Java WASM runtime), so there are no native deps. The
 * engine module has zero imports, so instantiation needs no host functions.
 */
final class WasmBridge {

    private static final String WASM_RESOURCE = "/klswitch_core.wasm";
    private static final int LEN_PREFIX = 4;

    private final Memory memory;
    private final ExportFunction createEngine;
    private final ExportFunction destroyEngine;
    private final ExportFunction loadRules;
    private final ExportFunction evaluate;
    private final ExportFunction getStatus;
    private final ExportFunction alloc;
    private final ExportFunction free;
    private final ExportFunction freeResult;

    private WasmBridge(Instance instance) {
        this.memory = instance.memory();
        this.createEngine = instance.export("ks_create_engine");
        this.destroyEngine = instance.export("ks_destroy_engine");
        this.loadRules = instance.export("ks_load_rules");
        this.evaluate = instance.export("ks_evaluate");
        this.getStatus = instance.export("ks_get_status");
        this.alloc = instance.export("ks_alloc");
        this.free = instance.export("ks_free");
        this.freeResult = instance.export("ks_free_result");
    }

    /** Load and instantiate the bundled engine module from the classpath. */
    static WasmBridge loadBundled() {
        try (InputStream in = WasmBridge.class.getResourceAsStream(WASM_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled " + WASM_RESOURCE + " not found on the classpath");
            }
            WasmModule module = Parser.parse(in.readAllBytes());
            Instance instance = Instance.builder(module).build();
            return new WasmBridge(instance);
        } catch (IOException e) {
            throw new RuntimeException("failed to load the bundled WASM engine", e);
        }
    }

    // A Chicory Instance has a single shared operand stack and one linear memory,
    // and the engine's handle table is a thread_local/RefCell — none of it is
    // concurrency-safe. The JVM is multi-threaded, so every engine call on this
    // instance is serialized here. (Callers needing parallelism use a pool of
    // Clients, each with its own instance.) The cached `memory` reference stays
    // valid across `ks_alloc` growth: Chicory reallocates the buffer *inside* the
    // same Memory object, unlike JS where the ArrayBuffer detaches.

    synchronized int createEngine() {
        return ptr(createEngine.apply()[0]);
    }

    synchronized void destroyEngine(int handle) {
        destroyEngine.apply(handle);
    }

    synchronized String loadRules(int handle, String rulesJson) {
        return callJson(loadRules, handle, rulesJson);
    }

    synchronized String evaluate(int handle, String inputJson) {
        return callJson(evaluate, handle, inputJson);
    }

    synchronized String getStatus(int handle) {
        return readAndFree(ptr(getStatus.apply(handle)[0]));
    }

    /** Coerce a returned WASM i32 pointer to an unsigned int offset (cf. Node's `>>> 0`). */
    private static int ptr(long raw) {
        return (int) (raw & 0xFFFF_FFFFL);
    }

    /** Write input bytes, call the export, read+free the result, free the input. */
    private String callJson(ExportFunction fn, int handle, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int inputPtr = ptr(alloc.apply(bytes.length)[0]);
        try {
            memory.write(inputPtr, bytes);
            int resultPtr = ptr(fn.apply(handle, inputPtr, bytes.length)[0]);
            return readAndFree(resultPtr);
        } finally {
            free.apply(inputPtr, bytes.length);
        }
    }

    /** Decode a length-prefixed result buffer ([u32 LE len][json]) and free it. */
    private String readAndFree(int resultPtr) {
        if (resultPtr == 0) {
            throw new IllegalStateException("engine returned a null result buffer");
        }
        try {
            int len = memory.readInt(resultPtr);
            byte[] out = memory.readBytes(resultPtr + LEN_PREFIX, len);
            return new String(out, StandardCharsets.UTF_8);
        } finally {
            freeResult.apply(resultPtr);
        }
    }
}
