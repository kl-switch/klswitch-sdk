package com.klswitch;

import java.util.Map;

/** Built-in {@link Logger} implementations. */
public final class LoggerAdapter {

    private LoggerAdapter() {}

    /** Silent logger — the default, so the SDK never writes uninvited. */
    public static final Logger NOOP = new Logger() {
        @Override
        public void debug(String message, Map<String, Object> fields) {}

        @Override
        public void info(String message, Map<String, Object> fields) {}

        @Override
        public void warn(String message, Map<String, Object> fields) {}

        @Override
        public void error(String message, Map<String, Object> fields) {}
    };

    /** Simple {@code System.out}/{@code System.err} logger, prefixed for grepability. */
    public static final Logger CONSOLE = new Logger() {
        @Override
        public void debug(String message, Map<String, Object> fields) {
            System.out.println("[klswitch] DEBUG " + message + " " + fields);
        }

        @Override
        public void info(String message, Map<String, Object> fields) {
            System.out.println("[klswitch] INFO " + message + " " + fields);
        }

        @Override
        public void warn(String message, Map<String, Object> fields) {
            System.err.println("[klswitch] WARN " + message + " " + fields);
        }

        @Override
        public void error(String message, Map<String, Object> fields) {
            System.err.println("[klswitch] ERROR " + message + " " + fields);
        }
    };
}
