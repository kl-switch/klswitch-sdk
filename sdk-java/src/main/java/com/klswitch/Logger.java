package com.klswitch;

import java.util.Map;

/**
 * Wrapper-owned logging. The engine never logs; the wrapper logs lifecycle and
 * refresh events (not every evaluation). Provide one via {@link Config}; the
 * default is silent ({@link LoggerAdapter#NOOP}).
 */
public interface Logger {

    void debug(String message, Map<String, Object> fields);

    void info(String message, Map<String, Object> fields);

    void warn(String message, Map<String, Object> fields);

    void error(String message, Map<String, Object> fields);

    default void debug(String message) {
        debug(message, Map.of());
    }

    default void info(String message) {
        info(message, Map.of());
    }

    default void warn(String message) {
        warn(message, Map.of());
    }

    default void error(String message) {
        error(message, Map.of());
    }
}
