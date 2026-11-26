import type { Logger } from "./logger.js";

/**
 * Header construction for every backend call (rules poll,
 * decisions). Built centrally so the wire-level shape stays consistent
 * across pollers / reporters and so SDK-controlled headers (auth,
 * instance key, user agent) can't be accidentally overridden by the
 * operator's `extraHeaders`.
 *
 * `X-Env-Hint` is observational — the backend derives the authoritative
 * env from the token. The hint exists so that a misconfigured deploy
 * (prod token distributed to staging pods) is loud rather than silent.
 */

const ENV_HINT_VARS = [
  "KS_ENV",
  "POD_NAMESPACE",
  "K_SERVICE_REVISION",
] as const;

export interface HeaderInputs {
  token:        string;
  instanceKey:  string;
  sdkVersion:   string;
  extraHeaders?: Record<string, string>;
  envHint?:     string;
  /** Override env source for tests. Defaults to `process.env`. */
  env?: NodeJS.ProcessEnv;
}

export function buildHeaders(input: HeaderInputs, logger?: Logger): Record<string, string> {
  const envHint = input.envHint ?? resolveEnvHint(input.env ?? process.env);

  const headers: Record<string, string> = {
    ...(input.extraHeaders ?? {}),
  };

  // SDK-controlled headers go last so they always win over operator extras.
  // Warn if the operator tried to set them — likely a misunderstanding.
  warnIfOverridden(input.extraHeaders, ["authorization", "x-instance-key", "user-agent"], logger);

  headers["Authorization"]   = `Bearer ${input.token}`;
  headers["X-Instance-Key"]  = input.instanceKey;
  headers["User-Agent"]      = `klswitch-node/${input.sdkVersion}`;
  headers["Accept-Encoding"] = "gzip";

  if (envHint) {
    headers["X-Env-Hint"] = envHint;
  }

  return headers;
}

/** First non-empty value from the standard env-hint variables. */
export function resolveEnvHint(env: NodeJS.ProcessEnv): string | undefined {
  for (const v of ENV_HINT_VARS) {
    const value = env[v];
    if (value && value.trim().length > 0) {
      return value.trim();
    }
  }
  return undefined;
}

function warnIfOverridden(
  extras: Record<string, string> | undefined,
  reserved: string[],
  logger: Logger | undefined,
): void {
  if (!extras || !logger) return;
  for (const key of Object.keys(extras)) {
    if (reserved.includes(key.toLowerCase())) {
      logger.warn("extraHeaders includes a reserved header — SDK value wins", { header: key });
    }
  }
}
