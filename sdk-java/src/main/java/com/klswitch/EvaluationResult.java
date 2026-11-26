package com.klswitch;

import java.util.List;

/** Result of evaluate (matches spec/schemas/evaluation-result.schema.json). */
public record EvaluationResult(
        boolean ok,
        List<MatchedRule> matched,
        long evaluated,
        long skipped,
        List<ErrorInfo> errors) {}
