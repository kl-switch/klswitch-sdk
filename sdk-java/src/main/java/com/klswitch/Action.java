package com.klswitch;

import java.util.Map;

/** A matched rule's structured action. The engine returns it; it is not executed here. */
public record Action(String type, Map<String, Object> payload) {}
