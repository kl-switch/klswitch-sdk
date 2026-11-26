package com.klswitch;

public record MatchedRule(String id, String name, long priority, Action action) {}
