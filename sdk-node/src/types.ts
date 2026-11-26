/**
 * Public JSON-contract types, mirroring `spec/schemas/`. These are the shapes
 * exchanged with the WASM engine; the wrapper does not interpret them.
 */

export interface ErrorInfo {
  code: string;
  message: string;
  rule_id?: string;
  field?: string;
}

export interface Action {
  type: string;
  payload?: Record<string, unknown>;
}

/** v1 supports only API selectors. */
export interface Selector {
  kind: "api";
  keys: { method: string; path: string };
}

export interface ConditionLeaf {
  field: string;
  operator: string;
  value?: unknown;
}

export type ConditionNode =
  | { all: ConditionNode[] }
  | { any: ConditionNode[] }
  | { not: ConditionNode }
  | ConditionLeaf;

export interface Rule {
  id: string;
  name: string;
  selector: Selector;
  enabled: boolean;
  priority?: number;
  condition: ConditionNode;
  action: Action;
  tags?: string[];
  metadata?: Record<string, unknown>;
}

export interface RuleSetPayload {
  schema_version: string;
  rules_version: number | string;
  rules: Rule[];
}

/** Fact buckets for condition field paths; all five must be present (may be empty). */
export interface Facts {
  headers: Record<string, unknown>;
  query: Record<string, unknown>;
  params: Record<string, unknown>;
  body: Record<string, unknown>;
  meta: Record<string, unknown>;
}

export interface EvaluationInput {
  selector: Selector;
  facts: Facts;
  /**
   * Trusted current time (epoch millis) used to gate rules with an effective
   * window. Optional — if omitted, `evaluate()` fills it with `Date.now()`.
   * Set it explicitly only to pin time (e.g. tests).
   */
  now?: number;
}

export interface MatchedRule {
  id: string;
  name: string;
  priority: number;
  action: Action;
}

export interface EvaluationResult {
  ok: boolean;
  matched: MatchedRule[];
  evaluated: number;
  skipped: number;
  errors: ErrorInfo[];
}

export interface LoadRulesResult {
  ok: boolean;
  rules_version: number;
  rule_count: number;
  compiled_rule_count: number;
  skipped_rule_count: number;
  errors: ErrorInfo[];
}

export interface EngineStatus {
  initialized: boolean;
  schema_version: string;
  active_rules_version: number | null;
  rule_count: number;
  last_load_ok: boolean;
  last_error: ErrorInfo | null;
}
