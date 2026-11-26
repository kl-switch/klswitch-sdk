/**
 * URL derivation from the config's `baseUrl` (with per-endpoint overrides
 * as escape hatches for self-hosted setups).
 *
 * Centralised so the polling manager, analytics reporter (A3), and manifest
 * reporter (A4) all build their URLs identically.
 */

export interface UrlInputs {
  baseUrl?:      string;
  rulesUrl?:     string;
  decisionsUrl?: string;
  manifestUrl?:  string;
}

export interface ResolvedUrls {
  rules:     string;
  decisions: string;
  manifest:  string;
}

export const DEFAULT_BASE_URL = "https://api.klswitch.dev";

export function buildUrls(input: UrlInputs): ResolvedUrls {
  const base = stripTrailingSlash(input.baseUrl ?? DEFAULT_BASE_URL);
  return {
    rules:     input.rulesUrl     ?? `${base}/v1/ruleset`,
    decisions: input.decisionsUrl ?? `${base}/v1/analytics`,
    manifest:  input.manifestUrl  ?? `${base}/v1/manifest`,
  };
}

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}
