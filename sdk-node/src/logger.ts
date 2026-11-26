/**
 * Wrapper-owned logging. The engine never logs; the wrapper logs lifecycle and
 * refresh events (not every evaluation). Provide a `Logger` via config to opt in;
 * the default is silent.
 */

export type LogLevel = "debug" | "info" | "warn" | "error";

export interface Logger {
  debug(message: string, fields?: Record<string, unknown>): void;
  info(message: string, fields?: Record<string, unknown>): void;
  warn(message: string, fields?: Record<string, unknown>): void;
  error(message: string, fields?: Record<string, unknown>): void;
}

/** Silent logger — the default, so the SDK never writes to stdout uninvited. */
export const noopLogger: Logger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
};

/** Simple `console`-backed logger, prefixed for grepability. */
export const consoleLogger: Logger = {
  debug: (m, f) => console.debug("[klswitch]", m, f ?? ""),
  info: (m, f) => console.info("[klswitch]", m, f ?? ""),
  warn: (m, f) => console.warn("[klswitch]", m, f ?? ""),
  error: (m, f) => console.error("[klswitch]", m, f ?? ""),
};
