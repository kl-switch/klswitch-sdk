"""Loaders for files bundled inside the package wheel."""

from __future__ import annotations

from importlib import resources


def bundled_wasm() -> bytes:
    """Return the bytes of the engine module shipped with the SDK."""
    return resources.files(__package__).joinpath("klswitch_core.wasm").read_bytes()
