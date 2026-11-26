"""Polling and the default HTTP rules-fetcher.

The polling manager runs a background daemon thread that calls ``fetch`` on a
:class:`RulesFetcher` at an interval; results are handed to ``on_fetched`` /
``on_error`` callbacks **without** the polling lock held (so a callback that
takes any other SDK lock cannot deadlock against the next poll).

Exponential backoff with ±25 % jitter (cap 60 s) keeps a failing upstream from
being hammered; jitter prevents multiple SDK instances from thundering-herd
retrying together.

**v1.1 server-driven polling.** :class:`HTTPFetcher` now implements
:class:`RulesFetcherWithMeta` — HEAD-first when an ETag has been cached,
conditional GET on flip (with ``If-None-Match`` race safety), and surfaces
``Cache-Control: max-age`` so the polling manager can retune its schedule.
Operator-injected fetchers implementing only the legacy :class:`RulesFetcher`
protocol keep working — the manager falls back to plain fetch + bootstrap
interval.

Defaults (5 s timeout, 4096-char error-body snippet in fetch errors, 1 MiB
bounded read) mirror the Java + Go defaults exactly so cross-language
behaviour stays comparable.
"""

from __future__ import annotations

import asyncio
import gzip as _gzip
import random
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Protocol, runtime_checkable

from ._json import decode_ruleset_payload
from .errors import KLSwitchError
from .logger import Logger, NullLogger
from .types import RuleSetPayload

# Defaults exposed for users and aligned with the Java/Go wrappers.
DEFAULT_REFRESH_TIMEOUT_SECONDS = 5.0
ERROR_BODY_SNIPPET_CHARS = 4096
DEFAULT_MAX_BODY_BYTES = 1 << 20  # 1 MiB
DEFAULT_REFRESH_INTERVAL_SECONDS = 30.0

_MAX_BACKOFF_EXPONENT = 6  # 2^6 = 64x before the absolute cap kicks in
#: Hard ceiling on the exponential-backoff delay after consecutive fetch
#: failures (per B2 acceptance criteria — 5 minutes). The effective cap
#: is ``max(MAX_BACKOFF_INTERVAL_SECONDS, current_interval)`` so backoff
#: never makes the wrapper poll more frequently than its success cadence.
MAX_BACKOFF_INTERVAL_SECONDS = 300.0

#: Floor on the server-driven poll cadence. Stops a server bug returning
#: ``max-age=0`` from turning every SDK pod into a tight self-DDoS loop.
MIN_POLL_INTERVAL_SECONDS = 5.0
#: Ceiling on the server-driven poll cadence. Keeps a misconfigured server
#: from silently parking pods for days at a time.
MAX_POLL_INTERVAL_SECONDS = 3600.0


def _clamp_poll_interval(seconds: float) -> float:
    if seconds <= 0:
        return MIN_POLL_INTERVAL_SECONDS
    if seconds < MIN_POLL_INTERVAL_SECONDS:
        return MIN_POLL_INTERVAL_SECONDS
    if seconds > MAX_POLL_INTERVAL_SECONDS:
        return MAX_POLL_INTERVAL_SECONDS
    return seconds


def _parse_max_age(header_value: str | None) -> float | None:
    """Extract ``max-age=N`` (seconds) from a Cache-Control header. Returns
    ``None`` when the directive is absent or unparseable — so the caller
    can distinguish "server didn't say" from "server explicitly said zero".
    """
    if not header_value:
        return None
    for directive in header_value.split(","):
        trimmed = directive.strip()
        if not trimmed.lower().startswith("max-age"):
            continue
        eq = trimmed.find("=")
        if eq < 0:
            continue
        try:
            n = int(trimmed[eq + 1:].strip())
        except ValueError:
            continue
        if n >= 0:
            return float(n)
    return None


class FetchError(KLSwitchError):
    """Raised by :class:`HTTPFetcher` when the rules API request fails.

    Caught and surfaced via :attr:`Status.last_refresh_error`; users calling
    :meth:`Client.refresh` directly see only ``None`` (refresh never re-raises
    fetch errors — check ``get_status`` for the outcome).
    """


@dataclass(frozen=True)
class FetchResult:
    """One poll cycle's outcome.

    ``not_modified`` is True when the backend confirmed no change (HEAD
    ETag match OR 304 conditional GET); in that case ``payload`` is
    ``None``. ``next_delay`` is ``None`` when the response carried no
    ``Cache-Control: max-age``; a non-``None`` zero means the server
    explicitly said ``max-age=0`` (polling manager clamps to
    :data:`MIN_POLL_INTERVAL_SECONDS`).
    """

    payload:      RuleSetPayload | None
    not_modified: bool
    etag:         str | None
    next_delay:   float | None  # seconds


class RulesFetcher(Protocol):
    """Fetches the current ruleset. Injectable so tests / custom transports work."""

    def fetch(self) -> RuleSetPayload: ...


@runtime_checkable
class RulesFetcherWithMeta(Protocol):
    """v1.1 server-driven polling surface. Fetchers that implement it return
    richer outcomes (304 / HEAD-match / next-poll cadence). The polling
    manager prefers this method when available and falls back to plain
    :class:`RulesFetcher` otherwise — operator-injected fetchers don't need
    to opt in.
    """

    def fetch(self) -> RuleSetPayload: ...

    def fetch_with_meta(self) -> FetchResult: ...


@dataclass
class HTTPFetcher:
    """GETs ``url`` with optional headers + a per-request timeout.

    v1.1: implements :class:`RulesFetcherWithMeta` via
    :meth:`fetch_with_meta`. HEAD-first when an ETag has been cached;
    conditional GET on flip. ``fetch`` keeps the legacy single-call
    semantics (always a plain GET) so operators holding the existing
    interface get unchanged behaviour.
    """

    url: str
    headers: dict[str, str] = field(default_factory=dict)
    # Seconds. Zero or negative → DEFAULT_REFRESH_TIMEOUT_SECONDS.
    timeout: float = DEFAULT_REFRESH_TIMEOUT_SECONDS
    # Cap on the response body read for both success + error paths.
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES

    # Internal state. Single fetcher instance is shared between polling
    # manager + manual refresh, but the manager serialises calls so a
    # plain attribute is safe; tests can read it via fetch_with_meta returns.
    _last_etag: str | None = field(default=None, init=False, repr=False)

    def fetch(self) -> RuleSetPayload:
        """Legacy single-call surface — always a plain GET, no ETag
        optimisation. The dance lives on :meth:`fetch_with_meta`.
        """
        resp = self._send("GET", conditional=False)
        body = self._read_body(resp)
        return self._decode(body)

    def fetch_with_meta(self) -> FetchResult:
        """HEAD-then-GET dance. Returns:

        * a fresh :class:`RuleSetPayload` (with the new ETag + cadence hint)
          when the backend delivered a new body
        * ``not_modified=True`` when the server confirmed nothing changed
          (HEAD ETag match OR 304 on the conditional GET)
        """
        cached = self._last_etag

        # Cold start: no cached ETag → straight to GET.
        if cached is None:
            resp = self._send("GET", conditional=False)
            body = self._read_body(resp)
            return self._fresh_result(resp, body)

        # Warm: HEAD probe first.
        head_resp = self._send("HEAD", conditional=False)
        head_etag = head_resp.headers.get("ETag")
        next_delay = _parse_max_age(head_resp.headers.get("Cache-Control"))
        if head_etag is not None and head_etag == cached:
            # Backend confirms our version. No GET, no apply.
            return FetchResult(
                payload=None, not_modified=True, etag=cached, next_delay=next_delay
            )

        # ETag flipped (or server omitted it). Do a conditional GET. The
        # server may reply 304 if the version flipped back during the
        # HEAD→GET window (race safety).
        try:
            resp = self._send("GET", conditional=True)
        except FetchError as e:
            # _send treats 304 as a special non-error result via the
            # _NotModified sentinel below — any other error stays an error.
            raise e
        body = self._read_body(resp)
        return self._fresh_result(resp, body)

    # ── internals ─────────────────────────────────────────────────────────

    def _send(
        self, method: str, *, conditional: bool,
    ) -> "_Response":
        timeout = self.timeout if self.timeout > 0 else DEFAULT_REFRESH_TIMEOUT_SECONDS
        headers = dict(self.headers)
        if conditional and self._last_etag:
            headers["If-None-Match"] = self._last_etag
        req = urllib.request.Request(self.url, headers=headers, method=method)
        try:
            raw_resp = urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            # urllib raises HTTPError for any non-2xx — including 304. For
            # the conditional GET path 304 is a legitimate response that
            # carries headers but no body; surface it as a NotModified
            # "fake response" so the caller can read max-age + treat it
            # as no-op. Any other status code becomes a FetchError with
            # the (capped) snippet attached.
            if conditional and e.code == 304:
                response = _Response(
                    status=304,
                    headers=_HeaderProxy(e.headers),
                    body=b"",
                )
                return response
            try:
                body_bytes = e.read(ERROR_BODY_SNIPPET_CHARS + 1)
            except Exception:  # noqa: BLE001 - defensive
                body_bytes = b""
            snippet = body_bytes.decode("utf-8", errors="replace")
            truncated = len(snippet) > ERROR_BODY_SNIPPET_CHARS
            if truncated:
                snippet = snippet[:ERROR_BODY_SNIPPET_CHARS] + "…"
            base = f"rules API {method} responded with status {e.code}"
            raise FetchError(base + (f": {snippet}" if snippet else "")) from e
        except urllib.error.URLError as e:
            raise FetchError(f"rules API request failed: {e.reason}") from e
        except OSError as e:
            raise FetchError(f"rules API request failed: {e}") from e

        response = _Response(
            status=raw_resp.status,
            headers=_HeaderProxy(raw_resp.headers),
            body=raw_resp.read(self.max_body_bytes + 1) if method != "HEAD" else b"",
        )
        try:
            raw_resp.close()
        except Exception:  # noqa: BLE001
            pass
        return response

    def _read_body(self, response: "_Response") -> bytes:
        if len(response.body) > self.max_body_bytes:
            raise FetchError(
                f"rules API response exceeded max_body_bytes ({self.max_body_bytes})",
            )
        return self._decompress(response)

    def _decompress(self, response: "_Response") -> bytes:
        """Gunzip the body when the server honoured our ``Accept-Encoding: gzip``.

        We set that header ourselves (``_request_headers``), and ``urllib`` — unlike
        Node's ``fetch`` — never decompresses transparently, so without this the raw
        deflate bytes reach ``_decode`` and blow up as a ``UnicodeDecodeError``. The
        Go wrapper does the same thing for the same reason (``polling.go``).
        """
        body = response.body
        encoding = (response.headers.get("Content-Encoding") or "").strip().lower()
        if encoding != "gzip" or not body:
            return body
        try:
            body = _gzip.decompress(body)
        except Exception as e:  # noqa: BLE001 - any gunzip failure is a fetch failure
            raise FetchError(f"rules API response could not be gunzipped: {e}") from e
        # Re-check the cap on the *inflated* size: the compressed check above cannot
        # bound what a hostile or misconfigured server expands to.
        if len(body) > self.max_body_bytes:
            raise FetchError(
                "rules API response exceeded max_body_bytes "
                f"({self.max_body_bytes}) after decompression",
            )
        return body

    def _fresh_result(self, response: "_Response", body: bytes) -> FetchResult:
        next_delay = _parse_max_age(response.headers.get("Cache-Control"))
        etag = response.headers.get("ETag")
        if response.status == 304:
            # Race: server reverted between HEAD and GET — keep the cached
            # ETag, no body to deliver.
            return FetchResult(
                payload=None,
                not_modified=True,
                etag=self._last_etag,
                next_delay=next_delay,
            )
        payload = self._decode(body)
        if etag:
            self._last_etag = etag
        return FetchResult(
            payload=payload,
            not_modified=False,
            etag=etag,
            next_delay=next_delay,
        )

    def _decode(self, raw: bytes) -> RuleSetPayload:
        try:
            return decode_ruleset_payload(raw.decode("utf-8"))
        except (UnicodeDecodeError, KeyError) as e:
            raise FetchError(f"rules API response is not a valid ruleset: {e}") from e
        except Exception as e:  # noqa: BLE001 - JSON decode + dataclass build can raise various
            raise FetchError(f"rules API response is not valid JSON: {e}") from e


@dataclass(frozen=True)
class _Response:
    """Uniform shape for HEAD / GET / 304 responses inside HTTPFetcher."""
    status: int
    headers: "_HeaderProxy"
    body: bytes


class _HeaderProxy:
    """Thin facade over either ``http.client.HTTPResponse.headers`` (an
    :class:`http.client.HTTPMessage`, case-insensitive) or
    ``HTTPError.headers`` — both expose ``.get(name, default=None)``.
    """

    def __init__(self, raw: object) -> None:
        self._raw = raw

    def get(self, name: str, default: str | None = None) -> str | None:
        try:
            value = self._raw.get(name, default)  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            return default
        return value


# ─────────────────────── polling manager (sync) ──────────────────────────────


OnFetched = Callable[[RuleSetPayload], None]
OnError = Callable[[Exception], None]


@dataclass(frozen=True)
class PollingStatus:
    """Snapshot of the polling state (sync + async share this shape)."""
    last_etag:           str | None
    next_poll_at:        str | None        # ISO 8601 UTC, None until loop running
    current_interval:    float             # seconds (server-driven if available)


class PollingManager:
    """Background-thread polling with serialised fetches and ±25 % jitter backoff.

    Prefers :meth:`RulesFetcherWithMeta.fetch_with_meta` when the supplied
    fetcher provides it (v1.1 server-driven polling); falls back to plain
    :meth:`RulesFetcher.fetch` for operator-injected fetchers.
    """

    def __init__(
        self,
        fetcher: RulesFetcher,
        interval: float,
        logger: Logger,
        on_fetched: OnFetched,
        on_error: OnError,
    ) -> None:
        self._fetcher = fetcher
        # The operator's configured interval — bootstrap default. NOT clamped:
        # operator explicitly chose it. _current_interval is clamped only
        # when retuned from a server response.
        self._bootstrap_interval = interval
        self._current_interval = interval
        self._logger = logger
        self._on_fetched = on_fetched
        self._on_error = on_error
        self._lock = threading.Lock()
        self._consecutive_failures = 0
        self._last_etag: str | None = None
        self._next_poll_at_ms: int | None = None
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._started = False
        self._supports_meta = isinstance(fetcher, RulesFetcherWithMeta) or hasattr(
            fetcher, "fetch_with_meta",
        )

    # ───── public ────────────────────────────────────────────────────────────

    def poll_once(self) -> None:
        """Fetch once. Callbacks fire **without** the lock held."""
        result: FetchResult | None = None
        rs: RuleSetPayload | None = None
        fetch_err: Exception | None = None
        with self._lock:
            try:
                if self._supports_meta:
                    result = self._fetcher.fetch_with_meta()  # type: ignore[attr-defined]
                    if not result.not_modified:
                        rs = result.payload
                else:
                    rs = self._fetcher.fetch()
            except Exception as e:  # noqa: BLE001 - any fetcher failure
                fetch_err = e
                self._consecutive_failures += 1
            else:
                self._consecutive_failures = 0
                if result is not None:
                    if result.etag:
                        self._last_etag = result.etag
                    if result.next_delay is not None:
                        self._current_interval = _clamp_poll_interval(result.next_delay)
            failures = self._consecutive_failures

        # Lock released — callbacks run without polling state held.
        if fetch_err is not None:
            self._logger.warning(
                "rules refresh failed; keeping last known good",
                {"error": str(fetch_err), "consecutive_failures": failures},
            )
            self._on_error(fetch_err)
            return
        if result is not None and result.not_modified:
            return  # backend confirmed no change — engine work is a no-op
        assert rs is not None
        self._on_fetched(rs)

    def status(self) -> PollingStatus:
        """Snapshot of the poll loop's state. Surfaced via Client.get_status()."""
        with self._lock:
            next_iso: str | None = None
            if self._next_poll_at_ms is not None:
                import datetime as _dt
                next_iso = (
                    _dt.datetime.utcfromtimestamp(self._next_poll_at_ms / 1000.0)
                    .replace(microsecond=0).isoformat() + "Z"
                )
            return PollingStatus(
                last_etag=self._last_etag,
                next_poll_at=next_iso,
                current_interval=self._current_interval,
            )

    def start(self) -> None:
        """Start the background poll loop. Idempotent."""
        if self._started:
            return
        self._started = True
        self._thread = threading.Thread(
            target=self._run, daemon=True, name="klswitch-poll"
        )
        self._thread.start()

    def close(self) -> None:
        """Signal the loop to stop and wait for the thread to exit."""
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=10.0)
            self._thread = None

    # ───── internals ────────────────────────────────────────────────────────

    def _run(self) -> None:
        while not self._stop_event.is_set():
            delay = self._next_delay()
            with self._lock:
                self._next_poll_at_ms = int((time.time() + delay) * 1000)
            # Interruptible sleep: returns True when stop is set, False on timeout.
            if self._stop_event.wait(timeout=delay):
                return
            self.poll_once()

    def _next_delay(self) -> float:
        with self._lock:
            fails = self._consecutive_failures
            current = self._current_interval
        if fails <= 0:
            return current
        exp = min(fails, _MAX_BACKOFF_EXPONENT)
        # Effective cap is max(MAX_BACKOFF_INTERVAL_SECONDS, current) — so a
        # long server-driven cadence (e.g. free-tier 5-min max-age) never
        # gets retried more aggressively than its success schedule.
        cap = max(MAX_BACKOFF_INTERVAL_SECONDS, current)
        grown = min(current * (1 << exp), cap)
        if grown <= 0:
            return cap
        # ±25 % jitter — [-grown/4, +grown/4) range, avoids synchronised retries.
        jitter_range = grown / 2.0
        return grown + (random.random() - 0.5) * jitter_range


# ─────────────────────── async polling primitives ────────────────────────────


class AsyncRulesFetcher(Protocol):
    """Async counterpart to :class:`RulesFetcher`. Used by :class:`AsyncClient`."""

    async def fetch(self) -> RuleSetPayload: ...


@runtime_checkable
class AsyncRulesFetcherWithMeta(Protocol):
    """Async counterpart to :class:`RulesFetcherWithMeta`."""

    async def fetch(self) -> RuleSetPayload: ...

    async def fetch_with_meta(self) -> FetchResult: ...


class _SyncFetcherToAsync:
    """Wrap a sync :class:`RulesFetcher` as an :class:`AsyncRulesFetcher`.

    Each call dispatches to ``asyncio.to_thread`` so the (potentially blocking)
    sync ``fetch`` doesn't stall the event loop. Used by :class:`AsyncClient`
    when the user passes ``rules_url=`` or a sync ``fetcher=``.

    If the wrapped fetcher implements :meth:`fetch_with_meta`, this adapter
    surfaces it too — preserving the v1.1 ETag optimisation for the async
    client when it uses the default HTTPFetcher.
    """

    def __init__(self, sync_fetcher: RulesFetcher) -> None:
        self._sync = sync_fetcher

    async def fetch(self) -> RuleSetPayload:
        return await asyncio.to_thread(self._sync.fetch)

    async def fetch_with_meta(self) -> FetchResult:
        if not hasattr(self._sync, "fetch_with_meta"):
            # Operator-injected fetcher without v1.1 surface — fall back to
            # legacy fetch and shape the result so async manager logic stays
            # uniform.
            payload = await asyncio.to_thread(self._sync.fetch)
            return FetchResult(
                payload=payload, not_modified=False, etag=None, next_delay=None,
            )
        return await asyncio.to_thread(self._sync.fetch_with_meta)  # type: ignore[attr-defined]


AsyncOnFetched = Callable[[RuleSetPayload], Awaitable[None]]
AsyncOnError = Callable[[Exception], Awaitable[None]]


class AsyncPollingManager:
    """asyncio version of :class:`PollingManager`.

    Same contract: serialised fetches (via :class:`asyncio.Lock`), callbacks
    fired without the lock held, ±25 % jittered exponential backoff (cap 60 s),
    interruptible sleep via :class:`asyncio.CancelledError`, and v1.1
    server-driven cadence when the fetcher implements ``fetch_with_meta``.
    """

    def __init__(
        self,
        fetcher: AsyncRulesFetcher,
        interval: float,
        logger: Logger,
        on_fetched: AsyncOnFetched,
        on_error: AsyncOnError,
    ) -> None:
        self._fetcher = fetcher
        self._bootstrap_interval = interval
        self._current_interval = interval
        self._logger = logger
        self._on_fetched = on_fetched
        self._on_error = on_error
        self._lock = asyncio.Lock()
        self._consecutive_failures = 0
        self._last_etag: str | None = None
        self._next_poll_at_ms: int | None = None
        self._task: asyncio.Task[None] | None = None
        self._stopped = False
        self._supports_meta = isinstance(fetcher, AsyncRulesFetcherWithMeta) or hasattr(
            fetcher, "fetch_with_meta",
        )

    async def poll_once(self) -> None:
        """Fetch once. Callbacks fire **without** the lock held."""
        result: FetchResult | None = None
        rs: RuleSetPayload | None = None
        fetch_err: Exception | None = None
        async with self._lock:
            try:
                if self._supports_meta:
                    result = await self._fetcher.fetch_with_meta()  # type: ignore[attr-defined]
                    if not result.not_modified:
                        rs = result.payload
                else:
                    rs = await self._fetcher.fetch()
            except Exception as e:  # noqa: BLE001 - any fetcher failure
                fetch_err = e
                self._consecutive_failures += 1
            else:
                self._consecutive_failures = 0
                if result is not None:
                    if result.etag:
                        self._last_etag = result.etag
                    if result.next_delay is not None:
                        self._current_interval = _clamp_poll_interval(result.next_delay)
            failures = self._consecutive_failures

        if fetch_err is not None:
            self._logger.warning(
                "rules refresh failed; keeping last known good",
                {"error": str(fetch_err), "consecutive_failures": failures},
            )
            await self._on_error(fetch_err)
            return
        if result is not None and result.not_modified:
            return
        assert rs is not None
        await self._on_fetched(rs)

    def status(self) -> PollingStatus:
        """Snapshot of the poll loop's state (cheap; no await needed)."""
        next_iso: str | None = None
        if self._next_poll_at_ms is not None:
            import datetime as _dt
            next_iso = (
                _dt.datetime.utcfromtimestamp(self._next_poll_at_ms / 1000.0)
                .replace(microsecond=0).isoformat() + "Z"
            )
        return PollingStatus(
            last_etag=self._last_etag,
            next_poll_at=next_iso,
            current_interval=self._current_interval,
        )

    def start(self) -> None:
        """Start the background poll task. Idempotent."""
        if self._task is not None:
            return
        self._task = asyncio.create_task(self._run(), name="klswitch-async-poll")

    async def _run(self) -> None:
        while not self._stopped:
            delay = self._next_delay()
            self._next_poll_at_ms = int((time.time() + delay) * 1000)
            try:
                await asyncio.sleep(delay)
            except asyncio.CancelledError:
                return
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                return

    def _next_delay(self) -> float:
        fails = self._consecutive_failures
        current = self._current_interval
        if fails <= 0:
            return current
        exp = min(fails, _MAX_BACKOFF_EXPONENT)
        cap = max(MAX_BACKOFF_INTERVAL_SECONDS, current)
        grown = min(current * (1 << exp), cap)
        if grown <= 0:
            return cap
        jitter_range = grown / 2.0
        return grown + (random.random() - 0.5) * jitter_range

    async def close(self) -> None:
        """Cancel the background task and wait for it to settle."""
        self._stopped = True
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
