"""Wrapper-owned logging.

The engine never logs; the wrapper logs lifecycle and refresh events (not every
evaluation). Pass a :class:`Logger` to :class:`Client`; the default is silent.

Method names follow Python's stdlib :mod:`logging` (``debug`` / ``info`` /
``warning`` / ``error``) so users implementing the Protocol can mirror what
they already know — at the small cost of differing slightly from the
``warn`` name the Node, Java, and Go SDKs use.
"""

from __future__ import annotations

import logging as _stdlib_logging
from typing import Any, Protocol


class Logger(Protocol):
    """Structured logger Protocol. Implement any subset; ``fields`` may be omitted."""

    def debug(self, msg: str, fields: dict[str, Any] | None = ...) -> None: ...
    def info(self, msg: str, fields: dict[str, Any] | None = ...) -> None: ...
    def warning(self, msg: str, fields: dict[str, Any] | None = ...) -> None: ...
    def error(self, msg: str, fields: dict[str, Any] | None = ...) -> None: ...


class NullLogger:
    """Silent logger — the default so the SDK never writes uninvited."""

    def debug(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        pass

    def info(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        pass

    def warning(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        pass

    def error(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        pass


class StdlibLogger:
    """Adapter from the SDK's :class:`Logger` Protocol to a stdlib :class:`logging.Logger`.

    Fields are forwarded via the ``extra={"klswitch": fields}`` convention so
    structured log handlers (e.g. JSON formatters) can pick them up cleanly.
    """

    def __init__(self, logger: _stdlib_logging.Logger | None = None) -> None:
        self._logger = logger or _stdlib_logging.getLogger("klswitch")

    def debug(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        self._log(_stdlib_logging.DEBUG, msg, fields)

    def info(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        self._log(_stdlib_logging.INFO, msg, fields)

    def warning(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        self._log(_stdlib_logging.WARNING, msg, fields)

    def error(self, msg: str, fields: dict[str, Any] | None = None) -> None:
        self._log(_stdlib_logging.ERROR, msg, fields)

    def _log(self, level: int, msg: str, fields: dict[str, Any] | None) -> None:
        if fields:
            self._logger.log(level, msg, extra={"klswitch": fields})
        else:
            self._logger.log(level, msg)
