package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class InputStreamWithDependencyTest {

    private static class Dependency implements AutoCloseable {
        boolean closed;
        Exception failure;

        @Override
        public void close() throws Exception {
            closed = true;
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Test
    public void delegatesReading() throws IOException {
        InputStream in = new InputStreamWithDependency(
                new BufferedInputStream(new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.US_ASCII))),
                new Dependency());
        assertEquals('a', in.read());
        byte[] buffer = new byte[2];
        assertEquals(2, in.read(buffer));
        assertEquals("bc", new String(buffer, StandardCharsets.US_ASCII));
        assertEquals(1, in.read(buffer, 1, 1));
        assertEquals(1, in.skip(1));
        assertEquals(1, in.available());
        assertTrue(in.markSupported());
        in.mark(10);
        assertEquals('f', in.read());
        in.reset();
        assertEquals('f', in.read());
        assertEquals(-1, in.read());
    }

    @Test
    public void closeClosesTheDependencyToo() throws IOException {
        Dependency dependency = new Dependency();
        new InputStreamWithDependency(new ByteArrayInputStream(new byte[0]), dependency).close();
        assertTrue(dependency.closed);
    }

    @Test
    public void dependencyIsClosedEvenIfTheStreamFailsToClose() {
        //e.g. a database cell stream: the connection must be released whatever happens to the stream
        Dependency dependency = new Dependency();
        InputStream failing = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                throw new IOException("stream close failed");
            }
        };
        IOException e = assertThrows(IOException.class, () -> new InputStreamWithDependency(failing, dependency).close());
        assertEquals("stream close failed", e.getMessage());
        assertTrue(dependency.closed);
    }

    @Test
    public void dependencyFailureIsReportedAsIOException() {
        Dependency dependency = new Dependency();
        dependency.failure = new IllegalStateException("connection broken");
        IOException e = assertThrows(IOException.class,
                () -> new InputStreamWithDependency(new ByteArrayInputStream(new byte[0]), dependency).close());
        assertEquals("connection broken", e.getCause().getMessage());
        assertFalse(e.getMessage().isEmpty());
    }
}
