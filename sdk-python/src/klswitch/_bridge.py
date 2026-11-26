"""WASM bridge over wasmtime-py.

Owns the JSON-in / JSON-out memory protocol: allocate an input buffer, write
UTF-8 bytes, call an export, read the length-prefixed result, free both. All
engine round-trips are serialised by a :class:`threading.Lock`, matching the
Java ``synchronized`` and Go ``sync.Mutex`` patterns — the engine's handle
table is single-threaded by design (WASM is single-threaded).
"""

from __future__ import annotations

import threading
from typing import Any, cast

import wasmtime

from .errors import EngineError

_LEN_PREFIX = 4
_U32_MASK = 0xFFFFFFFF


class Bridge:
    """Thread-safe wrapper over the ``ks_*`` WASM exports."""

    def __init__(self, wasm_bytes: bytes) -> None:
        self._engine = wasmtime.Engine()
        self._store = wasmtime.Store(self._engine)
        module = wasmtime.Module(self._engine, wasm_bytes)
        instance = wasmtime.Instance(self._store, module, [])
        exports = instance.exports(self._store)

        def _resolve(name: str) -> Any:
            exp = exports.get(name)
            if exp is None:
                raise EngineError(f"engine module missing export {name!r} (rebuild the WASM?)")
            return exp

        memory = _resolve("memory")
        if not isinstance(memory, wasmtime.Memory):
            raise EngineError("engine module's `memory` export is not Memory")
        self._memory: wasmtime.Memory = memory
        self._fn_create_engine = _resolve("ks_create_engine")
        self._fn_destroy_engine = _resolve("ks_destroy_engine")
        self._fn_load_rules = _resolve("ks_load_rules")
        self._fn_evaluate = _resolve("ks_evaluate")
        self._fn_get_status = _resolve("ks_get_status")
        self._fn_alloc = _resolve("ks_alloc")
        self._fn_free = _resolve("ks_free")
        self._fn_free_result = _resolve("ks_free_result")

        self._lock = threading.Lock()
        self._closed = False

    # ───── engine lifecycle ──────────────────────────────────────────────────

    def create_engine(self) -> int:
        with self._lock:
            self._check_open()
            handle = cast(int, self._fn_create_engine(self._store))
            return handle & _U32_MASK

    def destroy_engine(self, handle: int) -> None:
        with self._lock:
            if self._closed:
                return
            self._fn_destroy_engine(self._store, handle)

    # ───── JSON-in/JSON-out exports ──────────────────────────────────────────

    def load_rules(self, handle: int, rules_json: str) -> str:
        return self._call_json(self._fn_load_rules, handle, rules_json)

    def evaluate(self, handle: int, input_json: str) -> str:
        return self._call_json(self._fn_evaluate, handle, input_json)

    def get_status(self, handle: int) -> str:
        with self._lock:
            self._check_open()
            ptr = cast(int, self._fn_get_status(self._store, handle)) & _U32_MASK
            return self._read_and_free(ptr)

    def close(self) -> None:
        """Mark the bridge unusable. wasmtime cleans up on garbage collection;
        we just guard against post-close calls into a partially-torn-down state."""
        with self._lock:
            self._closed = True

    # ───── helpers (caller MUST hold _lock for the engine-call ones) ─────────

    def _check_open(self) -> None:
        if self._closed:
            raise EngineError("bridge is closed")

    def _call_json(self, fn: Any, handle: int, payload: str) -> str:
        with self._lock:
            self._check_open()
            data = payload.encode("utf-8")
            data_len = len(data)
            input_ptr = cast(int, self._fn_alloc(self._store, data_len)) & _U32_MASK
            if input_ptr == 0 and data_len > 0:
                raise EngineError(f"ks_alloc({data_len}) returned null")
            try:
                if data_len > 0:
                    self._memory.write(self._store, data, input_ptr)
                result_ptr = cast(int, fn(self._store, handle, input_ptr, data_len)) & _U32_MASK
                return self._read_and_free(result_ptr)
            finally:
                self._fn_free(self._store, input_ptr, data_len)

    def _read_and_free(self, ptr: int) -> str:
        if ptr == 0:
            raise EngineError("engine returned a null result buffer")
        prefix = self._memory.read(self._store, ptr, ptr + _LEN_PREFIX)
        length = int.from_bytes(prefix, "little", signed=False)
        body = self._memory.read(self._store, ptr + _LEN_PREFIX, ptr + _LEN_PREFIX + length)
        # Copy out before freeing so the bytes survive any subsequent allocation
        # (wasmtime-py's `read` already returns a fresh ``bytes`` object — Python
        # ``bytes`` are immutable, so this is more of a doc note than a guard).
        out = body.decode("utf-8")
        self._fn_free_result(self._store, ptr)
        return out
