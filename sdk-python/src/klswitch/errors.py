"""Public exception hierarchy."""

from __future__ import annotations


class KLSwitchError(Exception):
    """Base for every exception raised by this SDK."""


class ClosedClientError(KLSwitchError):
    """Raised when a method is called on a closed :class:`Client`."""

    def __init__(self) -> None:
        super().__init__("KLSwitch: client is not initialized (or has been closed)")


class LoadRulesError(KLSwitchError):
    """Raised by :meth:`Client.__init__` when the initial rule load fails.

    The structured engine result is attached as ``self.result`` so callers can
    inspect per-rule errors without parsing the message.
    """

    def __init__(self, detail: str, result: object | None = None) -> None:
        super().__init__(f"KLSwitch init: initial rules failed to load ({detail})")
        self.result = result


class EngineError(KLSwitchError):
    """Raised when the WASM engine itself misbehaves (bad pointer, etc.)."""
