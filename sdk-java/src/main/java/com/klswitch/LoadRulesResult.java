package com.klswitch;

import java.util.List;

/** Result of load_rules (matches spec/schemas/load-rules-result.schema.json). */
public record LoadRulesResult(
        boolean ok,
        long rulesVersion,
        long ruleCount,
        long compiledRuleCount,
        long skippedRuleCount,
        List<ErrorInfo> errors) {}
