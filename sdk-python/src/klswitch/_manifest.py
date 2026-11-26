"""API manifest reporting.

Each SDK instance, at boot, asks its host framework "what routes do you
have registered?" via a :class:`ManifestSource` the operator configures,
then POSTs the discovered route list to ``/v1/manifest``. The backend
uses this as the live truth of what's running — powering the console's
route picker on ``/rules/new``, field autocomplete, "stale route"
warnings, and Layer 4 of schema-drift detection.

Push policy:
  - One push at client init (fire-and-forget; never blocks startup).
  - On-demand re-push via :meth:`Client.report_manifest` /
    :meth:`AsyncClient.report_manifest`. The backend dedups by content
    hash, so calling this on a steady-state app is free.

Failure policy: log + counter bump. A failed push is an
operator-visibility regression, not an incident.

Opt-out: pass ``report_manifest=False`` to :class:`Client` /
:class:`AsyncClient`, or simply omit the ``manifest`` source.
Frameworks without a discoverable route registry simply skip the
reporter — the console falls back to free-text route entry with no
regression.
"""

from __future__ import annotations

import re

import asyncio
import hashlib
import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Mapping

from .logger import Logger, NullLogger

DEFAULT_MANIFEST_TIMEOUT_SECONDS = 5.0


@dataclass(frozen=True)
class ManifestField:
    """One discovered field on a route — best-effort, only when the
    framework supplies it.
    """

    name: str
    type: str


@dataclass
class ManifestRouteFields:
    """Best-effort field shape grouped by request location."""

    body:    list[ManifestField] = field(default_factory=list)
    headers: list[ManifestField] = field(default_factory=list)
    params:  list[ManifestField] = field(default_factory=list)
    query:   list[ManifestField] = field(default_factory=list)

    def to_wire_dict(self) -> dict:
        return {
            "body":    [{"name": f.name, "type": f.type} for f in self.body],
            "headers": [{"name": f.name, "type": f.type} for f in self.headers],
            "params":  [{"name": f.name, "type": f.type} for f in self.params],
            "query":   [{"name": f.name, "type": f.type} for f in self.query],
        }


@dataclass
class ManifestRoute:
    """One route discovered from the framework's registry."""

    method:  str                       # always uppercase
    path:    str                       # templated, e.g. "/v1/payments/:id"
    handler: str | None = None         # best-effort identifier
    fields:  ManifestRouteFields | None = None

    def to_wire_dict(self) -> dict:
        out: dict = {
            "method": self.method.upper(),
            "path":   self.path,
        }
        if self.handler:
            out["handler"] = self.handler
        if self.fields:
            out["fields"] = self.fields.to_wire_dict()
        return out


# Collectors may be sync or async; both flavours are supported transparently.
ManifestCollect = Callable[[], "list[ManifestRoute] | Awaitable[list[ManifestRoute]]"]


@dataclass
class ManifestSource:
    """What an operator passes to the SDK.

    Each framework adapter ships its own helper (e.g.
    :func:`fastapi_manifest_source`) that constructs one of these.
    """

    framework: str
    collect:   ManifestCollect


@dataclass(frozen=True)
class ManifestStatus:
    """Snapshot of the reporter's health, surfaced via
    :meth:`Client.manifest_status` / :meth:`AsyncClient.manifest_status`.
    """

    enabled:           bool
    framework:         str | None
    last_reported_at:  str | None
    last_hash:         str | None
    last_push_ok:      bool | None
    last_push_error:   str | None
    route_count:       int
    push_attempts:     int
    pushes_succeeded:  int
    pushes_failed:     int


def disabled_manifest_status() -> ManifestStatus:
    return ManifestStatus(
        enabled=False,
        framework=None,
        last_reported_at=None,
        last_hash=None,
        last_push_ok=None,
        last_push_error=None,
        route_count=0,
        push_attempts=0,
        pushes_succeeded=0,
        pushes_failed=0,
    )


_BRACE_PARAM = re.compile(r"\{([^:{}]+)(?::[^{}]+)?\}")


def canonicalise_path(path: str) -> str:
    """``{id}`` and ``{id:int}`` → ``:id``.

    The engine speaks one dialect. Applied in the reporter rather than in each
    adapter because the adapters disagreed: Spring and FastAPI converted, gorilla/mux
    and the hand-written sources did not, so five frameworks published
    /api/items/{id} while eight published /api/items/:id. A rule written from the
    console's route picker then matched nothing, because evaluation normalises to the
    colon form. Central also means a custom source cannot get it wrong — the escape
    hatch for frameworks with no route registry is exactly where someone types a path
    by hand. Idempotent; a converter spec like {id:int} is a routing constraint and is
    dropped.
    """
    if "{" not in path:
        return path
    return _BRACE_PARAM.sub(lambda m: f":{m.group(1)}", path)


def _normalise(routes: list[ManifestRoute]) -> list[ManifestRoute]:
    """Upper-case the method and sort by ``f"{METHOD} {path}"`` so the
    same registry produces the same hash regardless of registration order.
    """
    out = [
        ManifestRoute(
            method=r.method.upper(),
            path=canonicalise_path(r.path),
            handler=r.handler,
            fields=r.fields,
        )
        for r in routes
    ]
    out.sort(key=lambda r: f"{r.method} {r.path}")
    return out


def _hash_routes(routes: list[ManifestRoute]) -> str:
    """sha256 hex of the JSON-serialised (already-normalised) routes."""
    body = json.dumps(
        [r.to_wire_dict() for r in routes],
        ensure_ascii=False, separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(body).hexdigest()


def _post_manifest(
    url: str,
    headers: Mapping[str, str],
    body: bytes,
    timeout: float,
) -> int:
    """POST a JSON body and return the response status (raises on transport errors)."""
    merged: dict[str, str] = dict(headers)
    merged.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=body, headers=merged, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code


@dataclass
class ManifestReporter:
    """Sync manifest reporter. The async client wraps :meth:`report` in
    :func:`asyncio.to_thread` (see :class:`AsyncClient`).
    """

    url:          str
    headers:      Mapping[str, str]
    source:       ManifestSource
    instance_key: str
    logger:       Logger = field(default_factory=NullLogger)
    timeout:      float = DEFAULT_MANIFEST_TIMEOUT_SECONDS

    _last_hash:        str | None = field(default=None, init=False, repr=False)
    _last_reported_at: str | None = field(default=None, init=False, repr=False)
    _last_push_ok:     bool | None = field(default=None, init=False, repr=False)
    _last_push_error:  str | None = field(default=None, init=False, repr=False)
    _route_count:      int = field(default=0, init=False, repr=False)
    _push_attempts:    int = field(default=0, init=False, repr=False)
    _pushes_succeeded: int = field(default=0, init=False, repr=False)
    _pushes_failed:    int = field(default=0, init=False, repr=False)
    _stopped:          bool = field(default=False, init=False, repr=False)

    def report(self) -> bool:
        """Run discovery once and push if the hash has changed.
        Returns True on success or no-op; False on push failure.
        """
        if self._stopped:
            return False
        try:
            raw = self.source.collect()
            if asyncio.iscoroutine(raw):
                # Async collect from a sync context — punt to a fresh loop.
                routes = asyncio.run(raw)
            else:
                routes = raw  # type: ignore[assignment]
        except Exception as e:  # noqa: BLE001
            self._last_push_error = f"manifest source threw: {e}"
            self.logger.warning(
                "manifest source threw; skipping report",
                {"framework": self.source.framework, "error": str(e)},
            )
            self._push_attempts += 1
            self._pushes_failed += 1
            return False

        normalised = _normalise(routes)
        new_hash = _hash_routes(normalised)
        self._route_count = len(normalised)

        if self._last_hash == new_hash:
            # No change → backend already has it, no-op.
            return True

        self._push_attempts += 1
        ok = self._push(normalised, new_hash)
        if ok:
            self._last_hash = new_hash
            self._last_reported_at = _iso_now()
            self._last_push_ok = True
            self._last_push_error = None
            self._pushes_succeeded += 1
        else:
            self._last_push_ok = False
            self._pushes_failed += 1
        return ok

    def stop(self) -> None:
        """After this, :meth:`report` is a no-op. Idempotent."""
        self._stopped = True

    def status(self) -> ManifestStatus:
        return ManifestStatus(
            enabled=True,
            framework=self.source.framework,
            last_reported_at=self._last_reported_at,
            last_hash=self._last_hash,
            last_push_ok=self._last_push_ok,
            last_push_error=self._last_push_error,
            route_count=self._route_count,
            push_attempts=self._push_attempts,
            pushes_succeeded=self._pushes_succeeded,
            pushes_failed=self._pushes_failed,
        )

    # ── internals ────────────────────────────────────────────────────────

    def _push(self, routes: list[ManifestRoute], hash_hex: str) -> bool:
        payload = {
            "reportedAt":  _iso_now(),
            "instanceKey": self.instance_key,
            "framework":   self.source.framework,
            "hash":        hash_hex,
            "routes":      [r.to_wire_dict() for r in routes],
        }
        try:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        except Exception as e:  # noqa: BLE001
            self._last_push_error = f"serialise failed: {e}"
            return False
        try:
            status = _post_manifest(self.url, self.headers, body, self.timeout)
        except urllib.error.URLError as e:
            self._last_push_error = str(e.reason)
            self.logger.warning(
                "manifest POST failed",
                {"url": self.url, "error": str(e.reason)},
            )
            return False
        except OSError as e:
            self._last_push_error = str(e)
            self.logger.warning(
                "manifest POST failed",
                {"url": self.url, "error": str(e)},
            )
            return False
        if status >= 400:
            self._last_push_error = f"manifest POST returned status {status}"
            self.logger.warning(
                "manifest POST rejected",
                {"url": self.url, "status": status, "route_count": len(routes)},
            )
            return False
        self.logger.info(
            "manifest pushed",
            {"framework": self.source.framework,
             "route_count": len(routes), "hash": hash_hex},
        )
        return True


def _iso_now() -> str:
    import datetime as _dt
    return _dt.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


__all__ = [
    "DEFAULT_MANIFEST_TIMEOUT_SECONDS",
    "ManifestField",
    "ManifestReporter",
    "ManifestRoute",
    "ManifestRouteFields",
    "ManifestSource",
    "ManifestStatus",
    "disabled_manifest_status",
]
