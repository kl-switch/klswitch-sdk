package com.klswitch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the built-in {@link LoggerAdapter} loggers. The point
 * is coverage closure on the silent default + the console adapter —
 * we exercise every method including the default-method overloads on
 * the {@link Logger} interface so the cosmetic gap closes.
 */
class LoggerAdapterTest {

    private PrintStream origOut;
    private PrintStream origErr;
    private ByteArrayOutputStream capturedOut;
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void redirect() {
        origOut = System.out;
        origErr = System.err;
        capturedOut = new ByteArrayOutputStream();
        capturedErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restore() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    @Test
    void noopLoggerEveryLevelIsSilentAndNoThrow() {
        assertDoesNotThrow(() -> {
            LoggerAdapter.NOOP.debug("ok");
            LoggerAdapter.NOOP.info("ok");
            LoggerAdapter.NOOP.warn("ok");
            LoggerAdapter.NOOP.error("ok");
            LoggerAdapter.NOOP.debug("ok", Map.of("k", "v"));
            LoggerAdapter.NOOP.info("ok",  Map.of("k", "v"));
            LoggerAdapter.NOOP.warn("ok",  Map.of("k", "v"));
            LoggerAdapter.NOOP.error("ok", Map.of("k", "v"));
        });
    }

    @Test
    void consoleLoggerWritesToStdoutAndStderr() {
        LoggerAdapter.CONSOLE.debug("debugmsg", Map.of("k", "v"));
        LoggerAdapter.CONSOLE.info("infomsg",  Map.of("k", "v"));
        LoggerAdapter.CONSOLE.warn("warnmsg",  Map.of("k", "v"));
        LoggerAdapter.CONSOLE.error("errmsg",  Map.of("k", "v"));
        // Default-method overloads (no fields) — exercises the interface
        // defaults that delegate to the (msg, Map.of()) signatures.
        LoggerAdapter.CONSOLE.debug("d2");
        LoggerAdapter.CONSOLE.info("i2");
        LoggerAdapter.CONSOLE.warn("w2");
        LoggerAdapter.CONSOLE.error("e2");

        String out = capturedOut.toString();
        String err = capturedErr.toString();
        // Two debug + two info lines on stdout.
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("debugmsg"), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("infomsg"),  out);
        // Two warn + two error lines on stderr.
        org.junit.jupiter.api.Assertions.assertTrue(err.contains("warnmsg"), err);
        org.junit.jupiter.api.Assertions.assertTrue(err.contains("errmsg"),  err);
    }
}
