export { KLSwitch, SDK_VERSION } from "./client.js";
export {
  DEFAULT_REFRESH_INTERVAL_MS,
  DEFAULT_REFRESH_TIMEOUT_MS,
  validateConfig,
  type KLSwitchConfig,
} from "./config.js";
export {
  isValidInstanceId,
  resolveInstanceId,
  type InstanceIdResolution,
  type InstanceIdSource,
} from "./instance-id.js";
export {
  DEFAULT_BASE_URL,
  buildUrls,
  type ResolvedUrls,
  type UrlInputs,
} from "./url-builder.js";
export {
  buildHeaders,
  resolveEnvHint,
  type HeaderInputs,
} from "./request-headers.js";
export {
  DEFAULT_MANIFEST_TIMEOUT_MS,
  ManifestReporter,
  disabledManifestStatus,
  type ManifestField,
  type ManifestPayload,
  type ManifestReporterOptions,
  type ManifestRoute,
  type ManifestSource,
  type ManifestStatus,
} from "./manifest.js";
export {
  discoverExpressRoutes,
  expressManifestSource,
  extractMountPath as extractExpressMountPath,
  joinPaths as joinExpressPaths,
  type ExpressLikeApp,
  type ExpressLikeLayer,
  type ExpressLikeRouter,
} from "./manifest/express.js";
export {
  discoverFastifyRoutes,
  fastifyManifestSource,
  parsePrintRoutes,
  type FastifyLikeInstance,
} from "./manifest/fastify.js";
export {
  discoverNestjsRoutes,
  nestjsManifestSource,
  type NestLikeApp,
  type NestLikeHttpAdapter,
} from "./manifest/nestjs.js";
export { combineStatus, type KLSwitchStatus, type WrapperStatus } from "./status.js";
export { WasmBridge } from "./wasm-bridge.js";
export { consoleLogger, noopLogger, type Logger, type LogLevel } from "./logger.js";
export {
  ANALYTICS_MAX_BACKOFF_MS,
  AnalyticsReporter,
  DEFAULT_BATCH_SIZE,
  DEFAULT_FLUSH_INTERVAL_MS as DEFAULT_ANALYTICS_FLUSH_INTERVAL_MS,
  DEFAULT_MAX_QUEUE_SIZE,
  DEFAULT_POST_TIMEOUT_MS,
  LATENCY_BUCKETS,
  disabledAnalyticsStatus,
  latencyBucket,
  type AnalyticsEvent,
  type AnalyticsOptions,
  type AnalyticsSink,
  type AnalyticsStatus,
  type LatencyBucket,
  type ReporterTransport,
} from "./analytics.js";
export { PollingManager, type FetchLike, type PollingOptions } from "./polling-manager.js";
export { defaultCachePath, readCachedRuleset, writeCachedRuleset } from "./rules-cache.js";
export { canonicalizeTemplate,
  toEvaluationInput, pathWithoutQuery, type RequestParts } from "./request-context.js";
export {
  klswitchMiddleware,
  type ExpressAdapterOptions,
  type ExpressLikeRequest,
  type ExpressNext,
} from "./adapters/express.js";
export {
  klswitchHook,
  type FastifyAdapterOptions,
  type FastifyLikeRequest,
} from "./adapters/fastify.js";
export type {
  Action,
  ConditionLeaf,
  ConditionNode,
  EngineStatus,
  ErrorInfo,
  EvaluationInput,
  EvaluationResult,
  Facts,
  LoadRulesResult,
  MatchedRule,
  Rule,
  RuleSetPayload,
  Selector,
} from "./types.js";
