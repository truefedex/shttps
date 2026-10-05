package com.phlox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.testutil.RawHttpClient;
import com.phlox.server.testutil.TestServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a client is told when a handler throws. The messages of SecurityException and
 * IllegalStateException are a contract with the web UI, which shows them ("Identity already
 * used"); anything else is an unexpected failure whose details stay in the log.
 */
@Timeout(30)
public class SimpleHttpServerErrorMappingTest {

    private static RawHttpClient.Response respondTo(RequestHandler handler) throws Exception {
        try (TestServer server = new TestServer(handler).start();
             RawHttpClient client = server.connect()) {
            return client.get("/");
        }
    }

    @Test
    public void unexpectedFailureIsAGeneric500() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> {
            throw new RuntimeException("SQLITE_ERROR near C:\\Users\\owner\\secret.db");
        });
        assertEquals(500, response.code);
        assertEquals("Internal Server Error", response.bodyText());
        assertFalse(response.bodyText().contains("secret"));
        assertEquals("close", response.header("Connection"));
    }

    @Test
    public void exceptionWithoutMessageIsAGeneric500() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> {
            throw new NullPointerException();
        });
        assertEquals(500, response.code);
        assertEquals("Internal Server Error", response.bodyText());
    }

    @Test
    public void securityExceptionIs403WithItsMessage() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> {
            throw new SecurityException("Invalid table name: x");
        });
        assertEquals(403, response.code);
        assertEquals("Invalid table name: x", response.bodyText());
    }

    @Test
    public void illegalStateExceptionIs400WithItsMessage() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> {
            throw new IllegalStateException("Identity already used");
        });
        assertEquals(400, response.code);
        assertEquals("Identity already used", response.bodyText());
    }

    @Test
    public void bodyOverTheLimitIs413() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> {
            throw new com.phlox.server.utils.PayloadTooLargeException("Size limit exceeded: 10485760");
        });
        assertEquals(413, response.code);
        assertEquals("Size limit exceeded: 10485760", response.bodyText());
    }

    @Test
    public void nullResponseIs404() throws Exception {
        RawHttpClient.Response response = respondTo((context, request) -> null);
        assertEquals(404, response.code);
    }
}
