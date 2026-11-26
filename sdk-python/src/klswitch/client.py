"""Public :class:`Client` — the sync KLSwitch SDK entry point."""

from __future__ import annotations

import inspect
import threading
import time
from types import TracebackType

import socket

from . import _json
from ._analytics import (
    AnalyticsEvent,
    AnalyticsOptions,
    AnalyticsReporter,
    AnalyticsRequest,
    AnalyticsStatus,
    AnalyticsTransport,
    MatchedAction,
    disabled_analytics_status,
    latency_bucket,
)
from ._bridge import Bridge
from ._evaluation_observer import (
    AnalyticsEvaluationObserver,
    CompositeEvaluationObserver,
    EvaluationContext,
    ManifestRecheckObserver,
    NoopEvaluationObserver,
)
from ._client_base import (
    DEFAULT_REFRESH_INTERVAL_SECONDS,
    SDK_VERSION,
    _ClientBase,
)
from ._manifest import (
    ManifestReporter,
    ManifestSource,
    ManifestStatus,
    disabled_manifest_status,
)
from ._instance_id import InstanceIdResolution, resolve_instance_id
from ._polling import (
    DEFAULT_REFRESH_TIMEOUT_SECONDS,
    HTTPFetcher,
    PollingManager,
    PollingStatus,
    RulesFetcher,
)
from ._request_headers import build_headers
from ._resources import bundled_wasm
from ._rules_cache import read_cached_ruleset, write_cached_ruleset
from ._schema_drift import rules_by_id as _rules_by_id
from ._url_builder import ResolvedUrls, resolve_urls
from .errors import ClosedClientError, KLSwitchError, LoadRulesError
from .logger import Logger, NullLogger
from .status import Status
from .types import (
    EngineStatus,
    EvaluationInput,
    EvaluationResult,
    RuleSetPayload,
)

#: Pinned at build time so the user-agent reflects the shipped version.

#: Default polling cadence used when remote fetching is configured and
#: the operator hasn't passed ``refresh_interval`` explicitly.


class Client(_ClientBase):
    """Sync KLSwitch client. One ``Client`` owns one WASM engine instance.

    Safe for concurrent use from multiple threads — engine access is serialised
    internally by the bridge's lock.

    Lifecycle: ``__init__`` → ``evaluate`` / ``refresh`` / ``get_status`` →
    ``close``. Use as a context manager (``with Client(...) as ks:``) for
    automatic cleanup.
    """

    def __init__(
        self,
        *,
        token: str | None = None,
        instance_id: str | None = None,
        base_url: str | None = None,
        rules_url: str | None = None,
        decisions_url: str | None = None,
        manifest_url: str | None = None,
        rules: RuleSetPayload | None = None,
        refresh_interval: float | None = None,
        timeout: float | None = None,
        extra_headers: dict[str, str] | None = None,
        fetcher: RulesFetcher | None = None,
        analytics: AnalyticsOptions | None = None,
        manifest: ManifestSource | None = None,
        report_manifest: bool = True,
        cache_path: str | None = None,
        logger: Logger | None = None,
        custom_wasm: bytes | None = None,
    ) -> None:
        """Initialise the engine, apply rules, optionally start polling and analytics.

        :param token: Service-scoped bearer token. Required unless ``rules`` or
            ``fetcher`` is provided.
        :param instance_id: Override the auto-derived instance identity. Falls
            back through ``KS_INSTANCE_ID``, ``POD_NAME``, then
            ``hostname-pid-startMs`` derivation.
        :param base_url: Base URL of the KLSwitch backend (default
            ``https://api.klswitch.dev``). Per-endpoint overrides take
            precedence when set.
        :param rules_url: Override the rules endpoint URL (advanced /
            self-hosted).
        :param decisions_url: Override the decisions endpoint URL (advanced /
            self-hosted).
        :param rules: Inline ruleset loaded at init (dev-only). Bypasses the
            backend entirely.
        :param refresh_interval: Polling interval in seconds. ``None`` uses
            ``DEFAULT_REFRESH_INTERVAL_SECONDS`` (30 s). Pass ``0`` to disable
            polling.
        :param timeout: Per-request timeout (seconds) for the default HTTP fetcher.
        :param extra_headers: Additional headers sent on every backend call.
            Reserved headers (Authorization, X-Instance-Key, User-Agent) are
            SDK-controlled and cannot be overridden.
        :param fetcher: Custom :class:`RulesFetcher` (advanced / tests).
            Bypasses the default HTTPFetcher.
        :param analytics: Analytics options; reporter is created when
            ``analytics.sink`` is non-``None``.
        :param logger: Wrapper logger; default is silent.
        :param custom_wasm: Override the bundled engine bytes (advanced/tests).
        """
        self._mu = threading.Lock()
        self._logger: Logger = logger if logger is not None else NullLogger()

        # ── Validation ─────────────────────────────────────────────────────
        # A token is required UNLESS inline rules are supplied (dev) or a
        # custom fetcher is injected (advanced/tests). The default HTTP
        # fetcher needs the token for the Authorization header.
        remote_fetch_needs_token = rules is None and fetcher is None
        if remote_fetch_needs_token and (token is None or not token.strip()):
            raise ValueError("KLSwitch: token is required.")
        if refresh_interval is not None and refresh_interval < 0:
            raise ValueError(
                "refresh_interval must be >= 0 (pass 0 to disable polling)",
            )
        # analytics.sink is optional now — when omitted, the HTTP transport
        # ships batches to the backend (requires token). Sync Client still
        # rejects coroutine sinks at construction time so a misconfigured
        # caller doesn't silently spawn un-awaited coroutines.
        if analytics is not None and analytics.sink is not None:
            if not callable(analytics.sink):
                raise ValueError("analytics.sink must be callable")
            if inspect.iscoroutinefunction(analytics.sink):
                raise ValueError(
                    "sync Client requires a sync analytics sink — "
                    "use AsyncClient for coroutine sinks",
                )

        self._instance_id: InstanceIdResolution = resolve_instance_id(
            instance_id, logger=self._logger,
        )
        self._urls: ResolvedUrls = resolve_urls(
            base_url=base_url,
            rules_url=rules_url,
            decisions_url=decisions_url,
            manifest_url=manifest_url,
        )
        self._logger.info(
            "instance identity resolved",
            {"instance_key": self._instance_id.value, "source": self._instance_id.source.value},
        )

        self._bridge: Bridge | None = Bridge(custom_wasm or bundled_wasm())
        # Any failure past this point releases everything via close + re-raise.
        try:
            self._handle = self._bridge.create_engine()
            self._active_rules_version: int | None = None
            #: Active rules indexed by id — for schema-drift Layer 3 lookups
            #: in :meth:`evaluate`. Rebuilt atomically with
            #: ``_active_rules_version`` on every successful ruleset swap.
            self._rules_by_id: dict[str, "Rule"] = {}
            #: Operator-supplied disk-cache path from ``cache_path``.
            #: None when caching is off (the default).
            self._cache_path: str | None = cache_path
            self._polling: PollingManager | None = None
            self._polling_active = False
            self._observer = NoopEvaluationObserver()
            self._analytics: AnalyticsReporter | None = None
            self._manifest: ManifestReporter | None = None
            self._pending_error_count: int = 0
            self._last_refresh_at: int | None = None
            self._last_refresh_ok: bool | None = None
            self._last_refresh_error: str | None = None

            self._logger.info("wasm engine loaded", None)

            # 1. Inline rules first.
            if rules is not None:
                self._load_rules(rules)

            # 2. Remote rules. Skipped entirely on the dev-only inline-rules
            #    path AND when the operator injects a custom fetcher.
            if fetcher is None and token is not None and token.strip():
                headers = build_headers(
                    token=token,
                    instance_key=self._instance_id.value,
                    sdk_version=SDK_VERSION,
                    extra_headers=extra_headers,
                    logger=self._logger,
                )
                chosen_fetcher: RulesFetcher = HTTPFetcher(
                    url=self._urls.rules,
                    headers=headers,
                    timeout=timeout if timeout is not None else DEFAULT_REFRESH_TIMEOUT_SECONDS,
                )
                interval = (
                    DEFAULT_REFRESH_INTERVAL_SECONDS
                    if refresh_interval is None
                    else refresh_interval
                )
                self._polling = PollingManager(
                    fetcher=chosen_fetcher,
                    interval=interval,
                    logger=self._logger,
                    on_fetched=self._on_rules_fetched,
                    on_error=self._on_refresh_failed,
                )
                self._polling.poll_once()  # blocking initial fetch
                if interval > 0:
                    self._polling.start()
                    self._polling_active = True
            else:
                # Custom fetcher path or pure-inline-rules path.
                chosen_fetcher = self._build_fetcher(
                    token=token, fetcher=fetcher,
                    timeout=timeout, extra_headers=extra_headers,
                )
                if chosen_fetcher is not None:
                    interval = (
                        DEFAULT_REFRESH_INTERVAL_SECONDS
                        if refresh_interval is None
                        else refresh_interval
                    )
                    self._polling = PollingManager(
                        fetcher=chosen_fetcher,
                        interval=interval,
                        logger=self._logger,
                        on_fetched=self._on_rules_fetched,
                        on_error=self._on_refresh_failed,
                    )
                    self._polling.poll_once()  # blocking initial fetch
                    if interval > 0:
                        self._polling.start()
                        self._polling_active = True

            # 3. Boot-time disk-cache fallback: first fetch errored AND
            #    the operator opted in to caching AND a previous good
            #    ruleset is on disk → load it so we can serve traffic
            #    while polling keeps retrying the backend in the
            #    background.
            if self._active_rules_version is None and self._cache_path is not None:
                cached = read_cached_ruleset(self._cache_path, self._logger)
                if cached is not None:
                    self._logger.info(
                        "ruleset boot-cache hit",
                        {"path": self._cache_path},
                    )
                    self._apply_rules(cached)

            # 4. No rules from any source → fail cleanly.
            if self._active_rules_version is None:
                raise LoadRulesError(
                    "no rules available — pass rules= (dev) or set token= for the backend",
                )

            # 4. Analytics inside the try so a setup failure still releases the engine.
            #
            # When analytics is requested we wire one of:
            #   - HTTP transport (token set, no custom sink) → POSTs gzipped
            #     batches to /v1/decisions
            #   - Custom sink (operator-supplied) → bypasses HTTP
            if analytics is not None:
                if analytics.sink is not None:
                    transport = AnalyticsTransport(
                        sdk_lang="python",
                        sdk_version=SDK_VERSION,
                        instance_key=self._instance_id.value,
                        sink=analytics.sink,
                        post_timeout=analytics.post_timeout,
                    )
                else:
                    # No sink → require token-set HTTP path.
                    if token is None or not token.strip():
                        raise ValueError(
                            "analytics requires either a sink= or token= for the HTTP transport"
                        )
                    transport = AnalyticsTransport(
                        url=self._urls.decisions,
                        headers=build_headers(
                            token=token,
                            instance_key=self._instance_id.value,
                            sdk_version=SDK_VERSION,
                            extra_headers=extra_headers,
                            logger=self._logger,
                        ),
                        sdk_lang="python",
                        sdk_version=SDK_VERSION,
                        instance_key=self._instance_id.value,
                        post_timeout=analytics.post_timeout,
                    )
                self._analytics = AnalyticsReporter(analytics, transport, self._logger)

            # 5. Manifest reporting — only when a source was supplied AND we
            # have a backend to talk to AND the operator hasn't opted out.
            if (
                manifest is not None
                and report_manifest
                and token is not None
                and token.strip()
            ):
                manifest_headers = build_headers(
                    token=token,
                    instance_key=self._instance_id.value,
                    sdk_version=SDK_VERSION,
                    extra_headers=extra_headers,
                    logger=self._logger,
                )
                self._manifest = ManifestReporter(
                    url=self._urls.manifest,
                    headers=manifest_headers,
                    source=manifest,
                    instance_key=self._instance_id.value,
                    logger=self._logger,
                    timeout=timeout if timeout is not None
                            else DEFAULT_REFRESH_TIMEOUT_SECONDS,
                )
                # Fire-and-forget initial push on a background thread so a
                # slow/unreachable backend can't stall Client construction.
                threading.Thread(
                    target=self._manifest.report,
                    daemon=True,
                    name="klswitch-manifest-init",
                ).start()


            # Assemble the observers once, now that the reporters exist. Composing
            # here rather than branching inside evaluate() is what keeps the hot
            # path free of None checks and free of any knowledge of what telemetry
            # is configured.
            observers = []
            if self._analytics is not None:
                observers.append(AnalyticsEvaluationObserver(self._analytics))
            if self._manifest is not None:
                observers.append(ManifestRecheckObserver(self._manifest))
            if len(observers) == 1:
                self._observer = observers[0]
            elif observers:
                self._observer = CompositeEvaluationObserver(observers)

            self._logger.info(
                "client initialized",
                {
                    "rules_version": self._active_rules_version,
                    "instance_key": self._instance_id.value,
                    "polling": self._polling_active,
                    "analytics": self._analytics is not None,
                    "manifest":  self._manifest is not None,
                },
            )
        except BaseException:
            self.close()
            raise

    # ───── public API ────────────────────────────────────────────────────────

    def evaluate(self, evaluation_input: EvaluationInput) -> EvaluationResult:
        """Evaluate a request against the active ruleset.

        Evaluates, times the call, and tells the observer. It does not decide what
        is worth reporting, build a decision event, walk condition trees, or push a
        route manifest — all of which it used to do inline. Those are observers now.
        """
        bridge, handle = self._snapshot()
        with self._mu:
            observer = self._observer
            ver = self._active_rules_version
            rules_idx = self._rules_by_id

        start = time.perf_counter()
        out = bridge.evaluate(handle, _json.encode_evaluation_input(evaluation_input))
        result = _json.decode_evaluation_result(out)
        observer.on_evaluated(
            EvaluationContext(
                evaluation_input=evaluation_input,
                result=result,
                elapsed_seconds=time.perf_counter() - start,
                rules_version=ver,
                rules_by_id=rules_idx,
            ),
        )
        return result

    def polling_status(self) -> PollingStatus:
        """Snapshot of the polling loop's state: ``last_etag``,
        ``next_poll_at`` (ISO 8601 UTC), and ``current_interval``
        (server-driven via ``Cache-Control: max-age`` when available,
        otherwise the configured bootstrap interval). Returns a
        zero-state snapshot when polling isn't configured.
        """
        with self._mu:
            pm = self._polling
        if pm is None:
            return PollingStatus(
                last_etag=None, next_poll_at=None, current_interval=0.0,
            )
        return pm.status()

    def report_manifest(self) -> bool:
        """Re-run framework discovery and push the manifest if the route
        set has changed. Backend dedups by hash so calling this on a
        steady-state app is free. Useful after registering new routes
        at runtime (dev hot-reload, dynamic plugins).
        """
        with self._mu:
            rep = self._manifest
        return rep.report() if rep is not None else False

    def refresh(self) -> None:
        """Force an immediate fetch + reload from the backend.

        Requires that the client was initialised with a ``token`` (or custom
        ``fetcher``). Returns even if the fetch failed — the active ruleset is
        preserved and the failure is surfaced via :meth:`get_status`.
        """
        with self._mu:
            polling, bridge = self._polling, self._bridge
        if bridge is None:
            raise ClosedClientError()
        if polling is None:
            raise KLSwitchError(
                "KLSwitch refresh: no backend configured (init with token=)",
            )
        polling.poll_once()

    def close(self) -> None:
        """Stop polling + analytics, destroy the engine. Idempotent."""
        with self._mu:
            bridge = self._bridge
            handle = getattr(self, "_handle", 0)
            polling = getattr(self, "_polling", None)
            analytics = getattr(self, "_analytics", None)
            manifest  = getattr(self, "_manifest",  None)
            self._bridge = None
            self._handle = 0
            self._polling = None
            self._polling_active = False
            self._analytics = None
            self._manifest  = None

        if polling is not None:
            polling.close()
        if manifest is not None:
            manifest.stop()
        if analytics is not None:
            analytics.close()
        if bridge is not None:
            if handle != 0:
                bridge.destroy_engine(handle)
                self._logger.info("client closed", None)
            bridge.close()

    def __repr__(self) -> str:
        closed = self._bridge is None
        return (
            f"<klswitch.Client closed={closed} "
            f"active_rules_version={self._active_rules_version!r}>"
        )

    # ───── context manager ──────────────────────────────────────────────────

    def __enter__(self) -> Client:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # ───── internals ────────────────────────────────────────────────────────

    def _build_fetcher(
        self,
        *,
        token: str | None,
        fetcher: RulesFetcher | None,
        timeout: float | None,
        extra_headers: dict[str, str] | None,
    ) -> RulesFetcher | None:
        """Resolve the active rules fetcher. An operator-injected ``fetcher``
        takes precedence; otherwise — and only when a token is set — the
        default HTTPFetcher is constructed with auth + instance-key headers.
        Returns ``None`` for the dev-only inline-rules path.
        """
        if fetcher is not None:
            return fetcher
        if token is None or not token.strip():
            return None
        headers = build_headers(
            token=token,
            instance_key=self._instance_id.value,
            sdk_version=SDK_VERSION,
            extra_headers=extra_headers,
            logger=self._logger,
        )
        return HTTPFetcher(
            url=self._urls.rules,
            headers=headers,
            timeout=timeout if timeout is not None else DEFAULT_REFRESH_TIMEOUT_SECONDS,
        )

    def _load_rules(self, rules: RuleSetPayload) -> None:
        """Used at init (caller is the constructor)."""
        assert self._bridge is not None
        result = _json.decode_load_rules_result(
            self._bridge.load_rules(self._handle, _json.encode_ruleset(rules)),
        )
        if not result.ok:
            detail = "no rules compiled"
            if result.errors:
                e = result.errors[0]
                detail = f"{e.code}: {e.message}"
            raise LoadRulesError(detail, result=result)
        # Build the wrapper-side rules index BEFORE publishing the new
        # version — Layer-3 lookups during the same tick must never see
        # version N alongside version N-1's rules map.
        self._rules_by_id = _rules_by_id(rules.rules)
        self._active_rules_version = result.rules_version

    def _apply_rules(self, rules: RuleSetPayload) -> tuple[bool, str | None]:
        """Used by polling callbacks. Returns (ok, error_string_if_any)."""
        with self._mu:
            bridge, handle = self._bridge, getattr(self, "_handle", 0)
        if bridge is None or handle == 0:
            return False, "client closed"
        result = _json.decode_load_rules_result(
            bridge.load_rules(handle, _json.encode_ruleset(rules)),
        )
        if result.ok:
            new_idx = _rules_by_id(rules.rules)
            with self._mu:
                self._rules_by_id = new_idx
                self._active_rules_version = result.rules_version
            self._logger.info(
                "rules loaded",
                {
                    "rules_version": result.rules_version,
                    "rule_count": result.compiled_rule_count,
                    "skipped": result.skipped_rule_count,
                },
            )
            return True, None
        self._logger.warning(
            "rules load failed; keeping last known good",
            {"errors": [str(e) for e in result.errors]},
        )
        err = "no rules compiled"
        if result.errors:
            e = result.errors[0]
            err = f"{e.code}: {e.message}"
        return False, err

    def _on_rules_fetched(self, rules: RuleSetPayload) -> None:
        with self._mu:
            closed = self._bridge is None
        if closed:
            return
        ok, err = self._apply_rules(rules)
        with self._mu:
            self._last_refresh_at = int(time.time() * 1000)
            self._last_refresh_ok = ok
            self._last_refresh_error = err
            cache_path = self._cache_path
        # Persist to the boot cache only when the engine actually
        # accepted the ruleset — we don't want to overwrite a
        # known-good cache with a broken refresh.
        if ok and cache_path is not None:
            write_cached_ruleset(cache_path, rules, self._logger)



