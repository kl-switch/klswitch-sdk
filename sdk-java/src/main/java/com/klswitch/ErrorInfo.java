package com.klswitch;

/** Structured error (matches spec/schemas/error-info.schema.json). */
public record ErrorInfo(String code, String message, String ruleId, String field) {}
