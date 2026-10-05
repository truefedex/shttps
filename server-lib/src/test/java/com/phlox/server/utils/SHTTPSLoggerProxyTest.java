package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class SHTTPSLoggerProxyTest {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    public void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(captured, true));
    }

    @AfterEach
    public void restoreStdout() {
        System.setOut(originalOut);
        SHTTPSLoggerProxy.setFactory(tag -> new SHTTPSLoggerProxy.EmptyLogger());
    }

    private String output() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }

    private static String uniqueTag() {
        return "test-" + System.nanoTime();
    }

    @Test
    public void factoryDecidesTheLogger() {
        SHTTPSLoggerProxy.Logger logger = new SHTTPSLoggerProxy.EmptyLogger();
        SHTTPSLoggerProxy.setFactory(tag -> logger);
        assertSame(logger, SHTTPSLoggerProxy.getLogger(SHTTPSLoggerProxyTest.class));
        assertSame(logger, SHTTPSLoggerProxy.getLogger("tag"));
    }

    @Test
    public void eachLineIsPrintedOnceHoweverManyLoggersShareATag() {
        String tag = uniqueTag();
        new SHTTPSLoggerProxy.TaggedJavaLogger(tag);
        new SHTTPSLoggerProxy.TaggedJavaLogger(tag);
        new SHTTPSLoggerProxy.TaggedJavaLogger(tag).i("hello once");
        assertEquals(1, count(output(), "hello once"), output());
    }

    @Test
    public void debugLinesAreNotDroppedByJul() {
        new SHTTPSLoggerProxy.TaggedJavaLogger(uniqueTag()).d("debug line");
        assertTrue(output().contains("debug line"), output());
    }

    @Test
    public void errorWithThrowablePrintsMessageAndOneTrace() {
        String tag = uniqueTag();
        new SHTTPSLoggerProxy.TaggedJavaLogger(tag).e("something failed", new IllegalStateException("boom"));
        String out = output();
        assertTrue(out.contains("[" + tag + "] something failed"), out);
        assertEquals(1, count(out, "java.lang.IllegalStateException: boom"), out);
    }

    @Test
    public void maskFiltersLevelsAndTraces() {
        SHTTPSLoggerProxy.TaggedJavaLogger logger = new SHTTPSLoggerProxy.TaggedJavaLogger(uniqueTag(),
                SHTTPSLoggerProxy.Logger.ERROR | SHTTPSLoggerProxy.Logger.WARNING);
        logger.d("hidden debug");
        logger.i("hidden info");
        logger.w("shown warning");
        logger.e("shown error", new RuntimeException("no trace wanted"));
        logger.stackTrace(new RuntimeException("no trace either"));
        String out = output();
        assertFalse(out.contains("hidden"), out);
        assertTrue(out.contains("shown warning"), out);
        assertTrue(out.contains("shown error"), out);
        assertFalse(out.contains("no trace"), out);
    }
}
