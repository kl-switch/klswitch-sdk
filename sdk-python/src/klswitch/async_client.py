"""Public :class:`AsyncClient` — asyncio counterpart to :class:`Client`."""

from __future__ import annotations

import asyncio
import inspect
import threading
import time
from types import TracebackType

from . import _json
from ._analytics import (
    AnalyticsEvent,
    AnalyticsOptions,
    AnalyticsRequest,
    AnalyticsStatus,
    AnalyticsTransport,
    AsyncAnalyticsReporter,
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
from ._instance_id import InstanceIdResolution, resolve_instance_id
from ._manifest import (
    ManifestReporter,
    ManifestSource,
    ManifestStatus,
    disabled_manifest_status,
)
from ._polling import (
    DEFAULT_REFRESH_TIMEOUT_SECONDS,
    AsyncPollingManager,
    AsyncRulesFetcher,
    HTTPFetcher,
    PollingStatus,
    RulesFetcher,
    _SyncFetcherToAsync,
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


class AsyncClient(_ClientBase):
    """asyncio KLSwitch client.

    Lifecycle: ``__init__`` (sync setup + inline rules) → ``start()`` (initial
    fetch, polling task, analytics) → ``evaluate`` / ``refresh`` /
    ``get_status`` → ``aclose``.

    Idiomatic use is the async context manager::

        async with AsyncClient(token=...) as ks:
            result = await ks.evaluate(input)

    The bridge call inside ``evaluate`` is synchronous and brief (sub-ms for
    typical inputs), so it runs on the event loop thread by design. If a
    benchmark says you need to offload, supply a custom :class:`Logger` and
    wrap calls with :func:`asyncio.to_thread` from the application layer.
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
        fetcher: AsyncRulesFetcher | RulesFetcher | None = None,
        analytics: AnalyticsOptions | None = None,
        manifest: ManifestSource | None = None,
        report_manifest: bool = True,
        cache_path: str | None = None,
        logger: Logger | None = None,
        custom_wasm: bytes | None = None,
    ) -> None:
        """Sync setup. Initial remote fetch happens in :meth:`start` (or
        ``async with``). Inline ``rules`` are loaded here.

        See :class:`~klswitch.Client` for parameter docs — this signature is
        the asyncio mirror.
        """
        self._mu = threading.Lock()
        self._logger: Logger = logger if logger is not None else NullLogger()

        # Same validation as the sync Client.
        remote_fetch_needs_token = rules is None and fetcher is None
        if remote_fetch_needs_token and (token is None or not token.strip()):
            raise ValueError("KLSwitch: token is required.")
        if refresh_interval is not None and refresh_interval < 0:
            raise ValueError(
                "refresh_interval must be >= 0 (pass 0 to disable polling)",
            )
        if analytics is not None and analytics.sink is not None and not callable(analytics.sink):
            raise ValueError("analytics.sink must be callable")
        # A token-less analytics call with no sink is OK at construction time —
        # start() will refuse if neither sink nor token is present (we don't
        # know yet whether token is going to be supplied).

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
        try:
            self._handle = self._bridge.create_engine()
            self._active_rules_version: int | None = None
            #: Active rules indexed by id — for schema-drift Layer 3
            #: lookups in :meth:`evaluate`. Rebuilt atomically with
            #: ``_active_rules_version`` on every successful ruleset swap.
            self._rules_by_id: dict[str, "Rule"] = {}
            #: Operator-supplied disk-cache path from ``cache_path``.
            #: None when caching is off (the default).
            self._cache_path: str | None = cache_path
            self._started = False
            # Serialises concurrent ``start()`` calls so two coroutines can't
            # both proceed past the ``if self._started`` guard and spawn
            # duplicate polling/analytics tasks. asyncio.Lock is safe to
            # construct outside a running event loop (Python 3.10+).
            self._start_lock = asyncio.Lock()
            self._polling: AsyncPollingManager | None = None
            self._polling_active = False
            self._observer = NoopEvaluationObserver()
            self._analytics: AsyncAnalyticsReporter | None = None
            self._manifest: ManifestReporter | None = None
            self._pending_error_count: int = 0
            self._last_refresh_at: int | None = None
            self._last_refresh_ok: bool | None = None
            self._last_refresh_error: str | None = None

            # Stored config; start() consumes these.
            self._token = token
            self._refresh_interval = refresh_interval
            self._timeout = timeout
            self._extra_headers = extra_headers
            self._raw_fetcher = fetcher
            self._analytics_options = analytics
            self._manifest_source = manifest
            self._report_manifest = report_manifest

            self._logger.info("wasm engine loaded", None)

            if rules is not None:
                self._load_rules_sync(rules)

            # If there's no remote rules source AND no inline rules, init fails.
            # If only remote source is configured, fail is deferred to start().
            has_remote = self._token is not None or self._raw_fetcher is not None
            if self._active_rules_version is None and not has_remote:
                raise LoadRulesError(
                    "no rules available — pass rules= (dev) or set token= for the backend",
                )
        except BaseException:
            self._sync_release_on_init_failure()
            raise

    # ───── public API ────────────────────────────────────────────────────────

    async def start(self) -> None:
        """Perform the initial remote fetch (if any), start the polling task,
        and start the analytics reporter. Idempotent + race-safe — concurrent
        callers serialise on an asyncio.Lock so only one setup ever runs.

        Called automatically by ``async with``. If you use AsyncClient without
        the context manager and have ``token=`` configured, you must call
        this before :meth:`evaluate` or :meth:`refresh`.
        """
        async with self._start_lock:
            if self._started:
                return
            with self._mu:
                bridge = self._bridge
            if bridge is None:
                raise ClosedClientError()

            try:
                if self._raw_fetcher is None and self._token is not None and self._token.strip():
                    headers = build_headers(
                        token=self._token,
                        instance_key=self._instance_id.value,
                        sdk_version=SDK_VERSION,
                        extra_headers=self._extra_headers,
                        logger=self._logger,
                    )
                    sync_http = HTTPFetcher(
                        url=self._urls.rules,
                        headers=headers,
                        timeout=self._timeout
                        if self._timeout is not None
                        else DEFAULT_REFRESH_TIMEOUT_SECONDS,
                    )
                    async_fetcher: AsyncRulesFetcher = _SyncFetcherToAsync(sync_http)
                else:
                    chosen_fetcher = self._build_fetcher()
                    async_fetcher = (
                        self._wrap_async(chosen_fetcher) if chosen_fetcher is not None else None  # type: ignore[assignment]
                    )

                if async_fetcher is not None:
                    interval = (
                        DEFAULT_REFRESH_INTERVAL_SECONDS
                        if self._refresh_interval is None
                        else self._refresh_interval
                    )
                    self._polling = AsyncPollingManager(
                        fetcher=async_fetcher,
                        interval=interval,
                        logger=self._logger,
                        on_fetched=self._on_rules_fetched,
                        on_error=self._on_refresh_failed,
                    )
                    # Initial fetch.
                    await self._polling.poll_once()
                    if interval > 0:
                        self._polling.start()
                        self._polling_active = True

                # Boot-time disk-cache fallback: first fetch errored AND
                # the operator opted in to caching AND a previous good
                # ruleset is on disk → load it so we can serve traffic
                # while polling keeps retrying in the background.
                if self._active_rules_version is None and self._cache_path is not None:
                    cached = read_cached_ruleset(self._cache_path, self._logger)
                    if cached is not None:
                        self._logger.info(
                            "ruleset boot-cache hit",
                            {"path": self._cache_path},
                        )
                        self._apply_rules_sync(cached)

                if self._active_rules_version is None:
                    raise LoadRulesError(
                        "no rules available — pass rules= (dev) or set token= for the backend",
                    )

                if self._analytics_options is not None:
                    opts = self._analytics_options
                    if opts.sink is not None:
                        transport = AnalyticsTransport(
                            sdk_lang="python",
                            sdk_version=SDK_VERSION,
                            instance_key=self._instance_id.value,
                            sink=opts.sink,
                            post_timeout=opts.post_timeout,
                        )
                    else:
                        if self._token is None or not self._token.strip():
                            raise ValueError(
                                "analytics requires either a sink= or token= for the HTTP transport"
                            )
                        transport = AnalyticsTransport(
                            url=self._urls.decisions,
                            headers=build_headers(
                                token=self._token,
                                instance_key=self._instance_id.value,
                                sdk_version=SDK_VERSION,
                                extra_headers=self._extra_headers,
                                logger=self._logger,
                            ),
                            sdk_lang="python",
                            sdk_version=SDK_VERSION,
                            instance_key=self._instance_id.value,
                            post_timeout=opts.post_timeout,
                        )
                    self._analytics = AsyncAnalyticsReporter(opts, transport, self._logger)
                    self._analytics.start()

                # Manifest reporting — only when a source was supplied AND we
                # have a backend to talk to AND the operator hasn't opted out.
                # Built outside any specific fetcher branch so manifest still
                # works alongside operator-injected fetchers (provided a token
                # is present for the /v1/manifest POST headers).
                if (
                    self._manifest_source is not None
                    and self._report_manifest
                    and self._token is not None
                    and self._token.strip()
                ):
                    manifest_headers = build_headers(
                        token=self._token,
                        instance_key=self._instance_id.value,
                        sdk_version=SDK_VERSION,
                        extra_headers=self._extra_headers,
                        logger=self._logger,
                    )
                    self._manifest = ManifestReporter(
                        url=self._urls.manifest,
                        headers=manifest_headers,
                        source=self._manifest_source,
                        instance_key=self._instance_id.value,
                        logger=self._logger,
                        timeout=self._timeout
                        if self._timeout is not None
                        else DEFAULT_REFRESH_TIMEOUT_SECONDS,
                    )
                    # Fire-and-forget initial push on a worker thread so a
                    # slow/unreachable backend can't stall start().
                    asyncio.create_task(asyncio.to_thread(self._manifest.report))
            except BaseException:
                await self.aclose()
                raise

            with self._mu:
                self._started = True

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
                "async client started",
                {
                    "rules_version": self._active_rules_version,
                    "instance_key": self._instance_id.value,
                    "polling": self._polling_active,
                    "analytics": self._analytics is not None,
                    "manifest":  self._manifest is not None,
                },
            )


    async def evaluate(self, evaluation_input: EvaluationInput) -> EvaluationResult:
        """Evaluate a request against the active ruleset.

        Note the bridge call is synchronous; this method is ``async def`` so it
        composes with other async code, but the engine work itself blocks the
        event loop briefly.

        Evaluates, times the call, and tells the observer — see
        :meth:`Client.evaluate`.
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

    async def refresh(self) -> None:
        """Force an immediate fetch + reload. See :meth:`Client.refresh`."""
        with self._mu:
            polling, bridge = self._polling, self._bridge
        if bridge is None:
            raise ClosedClientError()
        if polling is None:
            raise KLSwitchError(
                "KLSwitch refresh: no backend configured (init with token=, or call start() first)",
            )
        await polling.poll_once()

    async def aclose(self) -> None:
        """Cancel polling, analytics, manifest, destroy the engine. Idempotent."""
        with self._mu:
            bridge = self._bridge
            handle = getattr(self, "_handle", 0)
            polling = self._polling
            analytics = self._analytics
            manifest  = getattr(self, "_manifest",  None)
            self._bridge = None
            self._handle = 0
            self._polling = None
            self._polling_active = False
            self._analytics = None
            self._manifest  = None
            self._started = False

        if polling is not None:
            await polling.close()
        if manifest is not None:
            manifest.stop()
        if analytics is not None:
            await analytics.close()
        if bridge is not None:
            if handle != 0:
                bridge.destroy_engine(handle)
                self._logger.info("client closed", None)
            bridge.close()

    def polling_status(self) -> PollingStatus:
        """Snapshot of the polling loop's state — see
        :meth:`Client.polling_status` for fields.
        """
        with self._mu:
            pm = self._polling
        if pm is None:
            return PollingStatus(
                last_etag=None, next_poll_at=None, current_interval=0.0,
            )
        return pm.status()

    async def report_manifest(self) -> bool:
        """Re-run framework discovery and push the manifest if the route
        set has changed. Backend dedups by hash so calling this on a
        steady-state app is free. Useful after registering new routes at
        runtime (dev hot-reload, dynamic plugins).

        Offloaded to a worker thread so the underlying ``urllib`` POST
        doesn't block the event loop.
        """
        with self._mu:
            rep = self._manifest
        if rep is None:
            return False
        return await asyncio.to_thread(rep.report)

    def __repr__(self) -> str:
        closed = self._bridge is None
        return (
            f"<klswitch.AsyncClient closed={closed} "
            f"started={getattr(self, '_started', False)} "
            f"active_rules_version={self._active_rules_version!r}>"
        )

    # ───── async context manager ─────────────────────────────────────────────

    async def __aenter__(self) -> AsyncClient:
        await self.start()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    # ───── internals ─────────────────────────────────────────────────────────

    def _build_fetcher(self) -> AsyncRulesFetcher | RulesFetcher | None:
        """Resolve the active rules fetcher (sync or async). Operator-injected
        fetcher takes precedence; otherwise the default HTTPFetcher is built
        from token + headers when a token is set.
        """
        if self._raw_fetcher is not None:
            return self._raw_fetcher
        if self._token is None or not self._token.strip():
            return None
        headers = build_headers(
            token=self._token,
            instance_key=self._instance_id.value,
            sdk_version=SDK_VERSION,
            extra_headers=self._extra_headers,
            logger=self._logger,
        )
        return HTTPFetcher(
            url=self._urls.rules,
            headers=headers,
            timeout=self._timeout
            if self._timeout is not None
            else DEFAULT_REFRESH_TIMEOUT_SECONDS,
        )

    def _wrap_async(
        self, fetcher: AsyncRulesFetcher | RulesFetcher,
    ) -> AsyncRulesFetcher:
        """Adapt the chosen fetcher to the async-polling interface."""
        if inspect.iscoroutinefunction(fetcher.fetch):
            return fetcher  # type: ignore[return-value]
        return _SyncFetcherToAsync(fetcher)  # type: ignore[arg-type]

    def _load_rules_sync(self, rules: RuleSetPayload) -> None:
        """Inline-rule load during __init__. Sync because no event loop yet."""
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

    def _apply_rules_sync(self, rules: RuleSetPayload) -> tuple[bool, str | None]:
        """Bridge LoadRules dispatched from the async callbacks. The bridge
        call itself is sync, but the surrounding context is the asyncio loop.
        """
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

    async def _on_rules_fetched(self, rules: RuleSetPayload) -> None:
        with self._mu:
            closed = self._bridge is None
        if closed:
            return
        ok, err = self._apply_rules_sync(rules)
        with self._mu:
            self._last_refresh_at = int(time.time() * 1000)
            self._last_refresh_ok = ok
            self._last_refresh_error = err
            cache_path = self._cache_path
        # Persist to the boot cache only when the engine actually
        # accepted the ruleset — we don't want to overwrite a known-good
        # cache with a broken refresh. Best-effort: blocking file IO is
        # offloaded so the event loop doesn't stall on slow disks.
        if ok and cache_path is not None:
            await asyncio.to_thread(
                write_cached_ruleset, cache_path, rules, self._logger,
            )

    def _sync_release_on_init_failure(self) -> None:
        """Synchronous best-effort release used inside ``__init__`` when an
        error fires before ``start()`` runs. Cannot use aclose() because we're
        not in an async context yet.
        """
        bridge = self._bridge
        handle = getattr(self, "_handle", 0)
        self._bridge = None
        self._handle = 0
        if bridge is not None:
            if handle != 0:
                try:
                    bridge.destroy_engine(handle)
                except Exception:  # noqa: BLE001
                    pass
            bridge.close()

    # ─── Async signatures over shared logic ────────────────────────────────────
    # Both of these are synchronous work that the async client exposes with an
    # awaitable signature, because that is what callers were given and changing it
    # would be a breaking change for no gain. The bodies live in _ClientBase; these
    # exist only so `await client.get_status()` keeps working.

    async def get_status(self) -> Status:
        """Combined engine + wrapper status. Safe after :meth:`aclose`."""
        return _ClientBase.get_status(self)

    async def _on_refresh_failed(self, err: Exception) -> None:
        _ClientBase._on_refresh_failed(self, err)
