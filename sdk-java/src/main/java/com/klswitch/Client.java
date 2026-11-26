package com.klswitch;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The public KLSwitch SDK client. One client owns one WASM engine instance plus
 * host-side concerns (polling, logging, analytics), all outside WASM.
 *
 * Thread-safe: engine access is serialized internally (see {@link WasmBridge}),
 * so a single client can be shared across request threads.
 */
public final class Client implements AutoCloseable {

    /** Pinned at build time so the user-agent reflects the shipped version. */
    public static final String SDK_VERSION = "0.1.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final EngineStatus CLOSED_STATUS =
            new EngineStatus(false, "1.0", null, 0, false, null);

    private WasmBridge bridge;
    private int handle;
    private Logger logger = LoggerAdapter.NOOP;
    private Collaborators.PollingControl polling;
    private boolean pollingActive;
    private Collaborators.AnalyticsControl analytics;
    private Collaborators.ManifestControl manifest;
    /** Notified after every evaluation. Never null — the no-op default keeps the
     *  hot path branch-free. */
    private volatile EvaluationObserver observer = EvaluationObserver.NOOP;

    /** Guards the one-shot manifest re-push in {@link #evaluate}. See
     *  {@link #recheckManifestOnce()}. */

    /** Description of the adapter that has mounted a guard on this client,
     *  or null while none has. Enforces one mount per client — see
     *  {@link #registerGuardMount(String)}. */
    private volatile String guardMount;
    private String startedAt;
    /** Opt-in cache path from {@code Config.cachePath}. null when caching is off. */
    private String cachePath;

    /** Resolved once at init; immutable for the process's lifetime. */
    private InstanceId.Resolution instanceId;
    /** Resolved once at init from baseUrl + overrides. */
    private UrlBuilder.Resolved urls;

    private volatile Long activeRulesVersion;
    /** Active rules' condition trees indexed by ruleId — for schema-drift
     *  Layer 3 lookups in {@link #evaluate}. Rebuilt atomically with
     *  {@code activeRulesVersion} on every successful ruleset swap. */
    private volatile Map<String, JsonNode> conditionsById = Map.of();
    private volatile Long lastRefreshAt;
    private volatile Boolean lastRefreshOk;
    /**
     * Rolling refresh-error counter, stamped on the next analytics event
     * and reset. Operators see refresh-side pain through the analytics
     * dashboard as well as via {@link #getStatus()}.
     */
    private final AtomicLong pendingErrorCount = new AtomicLong();

    private Client(WasmBridge bridge, int handle) {
        this.bridge = bridge;
        this.handle = handle;
    }

    /**
     * Load the WASM engine, load inline rules and/or fetch from the
     * configured rules endpoint, and start polling if configured. Fails
     * clearly (releasing the engine) if no rules can be loaded.
     */
    public static Client init(Config config) {
        Client client = new Client(WasmBridge.loadBundled(), 0);
        Collaborators.AnalyticsRecorder analyticsRecorder = null;
        client.logger = config.logger() != null ? config.logger() : LoggerAdapter.NOOP;
        client.instanceId = InstanceId.resolve(config.instanceId(), client.logger);
        client.urls = UrlBuilder.resolve(
                config.baseUrl(), config.rulesUrl(),
                config.decisionsUrl(), config.manifestUrl());
        client.cachePath = config.cachePath();
        client.logger.info("instance identity resolved", Map.of(
                "instanceKey", client.instanceId.value(),
                "source",      client.instanceId.source().name()));

        client.handle = client.bridge.createEngine();
        client.logger.info("wasm engine loaded");

        try {
            if (config.rulesJson() != null) {
                LoadRulesResult result = client.applyRules(config.rulesJson());
                if (!result.ok()) {
                    ErrorInfo first = result.errors().isEmpty() ? null : result.errors().get(0);
                    String detail = first != null
                            ? first.code() + ": " + first.message()
                            : "no rules compiled";
                    throw new IllegalStateException(
                            "KLSwitch init: initial rules failed to load (" + detail + ").");
                }
            }

            // Remote rules are fetched whenever a fetcher is configured: either
            // the operator injected one (advanced/tests), or a token is present
            // and we build the default HTTP fetcher with auth + instance-key
            // headers. The dev-only inline-rules path skips this entirely.
            if (config.token() != null && config.fetcher() == null) {
                client.startedAt = Instant.now().toString();
                Map<String, String> headers = RequestHeaders.build(
                        config.token(),
                        client.instanceId.value(),
                        SDK_VERSION,
                        config.extraHeaders(),
                        /*envHint=*/ null,
                        System::getenv,
                        client.logger);
                RulesFetcher fetcher = RulesFetcher.http(
                        client.urls.rules(), headers, config.timeoutMs());
                client.polling = new PollingManager(
                        fetcher, client.logger, client::onRulesFetched, client::onRefreshFailed);
                client.polling.pollOnce(); // initial fetch

                long interval = config.refreshIntervalMs() != null
                        ? config.refreshIntervalMs()
                        : Config.DEFAULT_REFRESH_INTERVAL_MS;
                if (interval > 0) {
                    client.polling.start(interval);
                    client.pollingActive = true;
                }
            } else if (config.fetcher() != null) {
                // Operator-injected fetcher path (advanced / tests).
                client.polling = new PollingManager(
                        config.fetcher(), client.logger, client::onRulesFetched, client::onRefreshFailed);
                client.polling.pollOnce();
                long interval = config.refreshIntervalMs() != null
                        ? config.refreshIntervalMs()
                        : Config.DEFAULT_REFRESH_INTERVAL_MS;
                if (interval > 0) {
                    client.polling.start(interval);
                    client.pollingActive = true;
                }
            }

            // Boot-time disk-cache fallback: first fetch errored AND the
            // operator opted in to caching AND a previous good ruleset is
            // on disk → load it so we can serve traffic while polling
            // keeps retrying the real backend in the background.
            if (client.activeRulesVersion == null && config.cachePath() != null) {
                String cached = RulesCache.read(config.cachePath(), client.logger);
                if (cached != null) {
                    client.logger.info("ruleset boot-cache hit",
                            Map.of("path", config.cachePath()));
                    client.applyRules(cached);
                }
            }

            if (client.activeRulesVersion == null) {
                throw new IllegalStateException(
                        "KLSwitch init: no rules available — provide inline rules (dev) "
                                + "or a reachable backend (set token).");
            }

            // Analytics setup is inside the try so a failure here still releases
            // the engine + polling thread via close(), instead of leaking them.
            //
            // When a token is set we build the default HTTP transport (gzipped
            // POST to /v1/decisions). When `analyticsSink` is supplied the
            // reporter routes batches to that sink and skips HTTP entirely.
            if (config.analyticsSink() != null || config.token() != null) {
                AnalyticsReporter.Transport transport;
                if (config.analyticsSink() != null) {
                    transport = AnalyticsReporter.Transport.sink(config.analyticsSink());
                } else {
                    Map<String, String> headers = RequestHeaders.build(
                            config.token(),
                            client.instanceId.value(),
                            SDK_VERSION,
                            config.extraHeaders(),
                            /*envHint=*/ null,
                            System::getenv,
                            client.logger);
                    transport = AnalyticsReporter.Transport.http(
                            client.urls.decisions(),
                            headers,
                            SDK_VERSION,
                            client.instanceId.value(),
                            config.timeoutMs());
                }
                // One object, two roles: the client gets the lifecycle half, the
                // observer gets the recording half. Keeping the concrete reference
                // is what lets each depend on only the interface it uses.
                AnalyticsReporter reporter = new AnalyticsReporter(
                        transport,
                        config.analyticsBatchSize(),
                        config.analyticsFlushIntervalMs(),
                        config.analyticsMaxQueueSize(),
                        config.timeoutMs(),
                        client.logger);
                client.analytics = reporter;
                analyticsRecorder = reporter;
            }

            // Manifest reporter — only when a source was supplied AND a
            // token is set AND the operator hasn't opted out. We need the
            // token for the /v1/manifest POST headers; the inline-rules
            // dev path skips manifest reporting entirely.
            if (config.manifest() != null && config.reportManifest() && config.token() != null) {
                Map<String, String> manifestHeaders = RequestHeaders.build(
                        config.token(),
                        client.instanceId.value(),
                        SDK_VERSION,
                        config.extraHeaders(),
                        /*envHint=*/ null,
                        System::getenv,
                        client.logger);
                client.manifest = ManifestReporter.builder()
                        .url(client.urls.manifest())
                        .headers(manifestHeaders)
                        .source(config.manifest())
                        .instanceKey(client.instanceId.value())
                        .timeoutMs(config.timeoutMs())
                        .logger(client.logger)
                        .build();
                // Fire-and-forget initial push on a daemon thread so a
                // slow/unreachable backend can't stall init.
                final Collaborators.ManifestControl mref = client.manifest;
                Thread t = new Thread(mref::report, "klswitch-manifest-init");
                t.setDaemon(true);
                t.start();
            }
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        }

        // Assemble the observers once, now that the reporters exist. Composing
        // here rather than branching inside evaluate() is what keeps the hot path
        // free of null checks and free of any knowledge of what telemetry is
        // configured.
        java.util.List<EvaluationObserver> observers = new java.util.ArrayList<>(2);
        if (analyticsRecorder != null) {
            observers.add(new AnalyticsEvaluationObserver(analyticsRecorder));
        }
        if (client.manifest != null) {
            observers.add(new ManifestRecheckObserver(client.manifest));
        }
        if (observers.size() == 1) {
            client.observer = observers.get(0);
        } else if (!observers.isEmpty()) {
            client.observer = new CompositeEvaluationObserver(observers);
        }

        client.logger.info("client initialized", Map.of(
                "rulesVersion", String.valueOf(client.activeRulesVersion),
                "instanceKey",  client.instanceId.value(),
                "manifest",     String.valueOf(client.manifest != null)));
        return client;
    }

    /** The resolved instance identifier. Stable for the process's lifetime. */
    public String instanceId() {
        if (instanceId == null) {
            throw new IllegalStateException("KLSwitch: client is not initialized.");
        }
        return instanceId.value();
    }

    /** The resolved backend URLs (rules, decisions, manifest). */
    public UrlBuilder.Resolved urls() {
        if (urls == null) {
            throw new IllegalStateException("KLSwitch: client is not initialized.");
        }
        return urls;
    }

    /** SDK version string, surfaced for adapters that want to log it. */
    public String sdkVersion() {
        return SDK_VERSION;
    }

    /**
     * Evaluate an EvaluationInput (JSON) against the active ruleset. Records analytics if enabled.
     *
     * <p><b>Rules with an effective window need a {@code now}.</b> This method takes the input
     * already serialized, so it cannot inject the clock without re-parsing. Build the input with
     * {@link RequestContext#toEvaluationInput} (every bundled adapter does) and {@code now} is
     * filled in for you. If you hand-build the JSON, add a top-level
     * {@code "now": System.currentTimeMillis()} — otherwise rules carrying an {@code effective}
     * window never fire (the engine treats a missing clock as fail-safe/inactive).
     */
    /**
     * Declare that an adapter has mounted a request guard on this client.
     * Called by the servlet and JAX-RS filters from their constructors, so
     * a second mount is refused while the app is still wiring up rather
     * than after it starts serving traffic.
     *
     * <p>Two guards on one client evaluate every request twice. The extra
     * latency is the smaller half: the second evaluation also emits its own
     * analytics event, so decision counts read double for those routes and
     * nothing about the responses looks wrong.
     *
     * @param description human-readable mount point, e.g. {@code "servlet filter"}
     * @throws IllegalStateException if a guard is already mounted
     */
    public void registerGuardMount(String description) {
        String existing = guardMount;
        if (existing != null) {
            throw new IllegalStateException(
                    "KLSwitch is already guarded by " + existing + "; refusing to also mount "
                    + description + ". Two guards on one client evaluate every request twice, "
                    + "which doubles decision counts in analytics while the responses still look "
                    + "correct. Mount exactly one guard per client, or build a second Client if "
                    + "these really are separate surfaces.");
        }
        guardMount = description;
    }

    /**
     * Push the manifest once more, the first time a request is evaluated.
     *
     * <p>The initial push in {@link #init} runs as soon as the client exists,
     * but framework routes are typically registered afterwards — in Dropwizard
     * the ordering is forced, since the resources need the client to construct
     * the guard. Discovery would therefore report an empty route set and the
     * console would have nothing to suggest rules against. The first evaluation
     * proves routing is live, so re-running discovery then reports the real set.
     * The backend dedups by hash, so a steady-state app pays nothing.
     */

    /**
     * Evaluate a request against the active ruleset.
     *
     * <p>Evaluates, times the call, and tells the observer. It does not decide
     * what is worth reporting, build a decision event, walk condition trees, or
     * push a route manifest — all of which it used to do inline. Those are
     * observers now, so this method has one reason to change and telemetry can
     * change without touching the hot path.
     */
    public EvaluationResult evaluate(String inputJson) {
        WasmBridge b = activeBridge();
        long start = System.nanoTime();
        EvaluationResult result = parse(b.evaluate(handle, inputJson), EvaluationResult.class);
        observer.onEvaluated(new EvaluationObserver.Context(
                inputJson,
                result,
                (System.nanoTime() - start) / 1_000_000.0,
                activeRulesVersion,
                conditionsById));
        return result;
    }

    /**
     * Force an immediate fetch + reload from the backend. Requires that the
     * client was initialized with a {@code token} (or custom fetcher).
     */
    public void refresh() {
        activeBridge();
        if (polling == null) {
            throw new UnsupportedOperationException(
                    "KLSwitch refresh: no backend configured (init with a token).");
        }
        polling.pollOnce();
    }

    /** Combined wrapper + engine status. Safe to call after {@code close}. */
    public Status getStatus() {
        WasmBridge b = bridge;
        boolean ready = b != null && handle != 0;
        EngineStatus engine = ready ? parse(b.getStatus(handle), EngineStatus.class) : CLOSED_STATUS;
        return Status.of(engine, ready, pollingActive, lastRefreshAt, lastRefreshOk);
    }

    /** Stop polling/analytics, destroy the engine, and release the WASM runtime. Idempotent. */
    @Override
    public void close() {
        if (polling != null) {
            polling.close();
            polling = null;
        }
        pollingActive = false;
        if (manifest != null) {
            manifest.close();
            manifest = null;
        }
        if (analytics != null) {
            analytics.close();
            analytics = null;
        }
        WasmBridge b = bridge;
        if (b != null && handle != 0) {
            b.destroyEngine(handle);
            logger.info("client closed");
        }
        handle = 0;
        bridge = null;
    }

    public Long activeRulesVersion() {
        return activeRulesVersion;
    }

    /** Load a ruleset into the engine, tracking the active version. Engine keeps last-known-good on failure. */
    private LoadRulesResult applyRules(String rulesJson) {
        LoadRulesResult result = parse(activeBridge().loadRules(handle, rulesJson), LoadRulesResult.class);
        if (result.ok()) {
            // Build the wrapper-side condition index BEFORE publishing the
            // new version — Layer-3 lookups during the same tick must
            // never see version N alongside version N-1's conditions.
            Map<String, JsonNode> conds = SchemaDrift.indexConditions(rulesJson);
            conditionsById = conds;
            activeRulesVersion = result.rulesVersion();
            logger.info("rules loaded", Map.of(
                    "rulesVersion", result.rulesVersion(),
                    "ruleCount", result.compiledRuleCount(),
                    "skipped", result.skippedRuleCount()));
        } else {
            logger.warn("rules load failed; keeping last known good",
                    Map.of("errors", String.valueOf(result.errors())));
        }
        return result;
    }

    private void onRulesFetched(String rulesJson) {
        if (bridge == null) {
            return; // a poll resolved after close
        }
        LoadRulesResult result = applyRules(rulesJson);
        lastRefreshAt = System.currentTimeMillis();
        lastRefreshOk = result.ok();
        // Persist to the boot cache only when the engine actually accepted
        // the ruleset — we don't want to overwrite a known-good cache
        // with a broken refresh.
        if (result.ok() && cachePath != null) {
            RulesCache.write(cachePath, rulesJson, logger);
        }
    }

    private void onRefreshFailed(Throwable error) {
        if (bridge == null) {
            return;
        }
        lastRefreshAt = System.currentTimeMillis();
        lastRefreshOk = false;
        // Refresh failures are an instance-operational signal, not stamped
        // onto decision events (which are pure per-evaluation facts).
        pendingErrorCount.incrementAndGet();
    }

    /** Snapshot the analytics reporter's status block (or a "disabled" block). */
    public AnalyticsStatus analyticsStatus() {
        return analytics != null ? analytics.status() : AnalyticsStatus.disabled();
    }

    /** Snapshot the manifest reporter's status block (or a "disabled" block). */
    public ManifestStatus manifestStatus() {
        return manifest != null ? manifest.status() : ManifestStatus.disabled();
    }

    /**
     * Snapshot the polling loop's state: {@code lastEtag}, {@code nextPollAt}
     * (ISO 8601), and {@code currentIntervalMs} (server-driven when available,
     * otherwise the configured bootstrap interval). Returns a disabled snapshot
     * when polling isn't configured (inline-rules dev path).
     */
    public PollingManager.PollingStatus pollingStatus() {
        return polling != null
                ? polling.status()
                : new PollingManager.PollingStatus(null, null, 0L);
    }

    /**
     * Re-run framework discovery and push the manifest if the route set
     * has changed. Backend dedups by hash so calling this on a
     * steady-state app is free. Useful after registering new routes at
     * runtime (dev hot-reload, dynamic plugins).
     */
    public boolean reportManifest() {
        Collaborators.ManifestControl m = manifest;
        return m != null && m.report();
    }

    /**
     * Pull the templated request shape out of an EvaluationInput's selector.
     * Falls back to safe defaults for non-API selectors so the analytics
     * event always satisfies the console schema.
     */
    static AnalyticsEvent.Request extractRequest(String inputJson) {
        try {
            JsonNode root = MAPPER.readTree(inputJson);
            JsonNode keys = root.path("selector").path("keys");
            String method = keys.path("method").asText("UNKNOWN").toUpperCase();
            String path   = keys.path("path").asText("");
            return new AnalyticsEvent.Request(method, path);
        } catch (Exception e) {
            return new AnalyticsEvent.Request("UNKNOWN", "");
        }
    }

    private WasmBridge activeBridge() {
        WasmBridge b = bridge;
        if (b == null || handle == 0) {
            throw new IllegalStateException("KLSwitch: client is not initialized (or has been closed).");
        }
        return b;
    }

    private static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse engine response: " + json, e);
        }
    }
}
