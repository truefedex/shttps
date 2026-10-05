package com.phlox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.handlers.router.Router;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.request.Request;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.testutil.RawHttpClient;
import com.phlox.server.testutil.TestServer;
import com.phlox.server.utils.Utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;

/**
 * A request the server can not make sense of gets an answer saying so, and the connection
 * is closed - before, it was dropped without a word, or a handler hit a NullPointerException.
 */
@Timeout(30)
public class SimpleHttpServerBadRequestTest {

    private static Router router() {
        //CORS in front of everything: it used to throw NPE on a request without a method
        Router router = new Router(null, Collections.<Middleware>singletonList(
                new CORSMiddleware(Collections.<CORSMiddleware.CORSRule>emptyList())));
        router.addRoute("/", new HashSet<>(Arrays.asList(Request.METHOD_GET, Request.METHOD_POST)),
                (context, request) -> StandardResponses.OK("ok"));
        router.addRoute("/echo", Collections.singleton(Request.METHOD_POST), (context, request) -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Utils.copyStream(request.bodyStream, body);
            return StandardResponses.OK(body.toString("UTF-8"));
        });
        return router;
    }

    private static RawHttpClient.Response exchange(String raw) throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send(raw);
            RawHttpClient.Response response = client.readResponse();
            assertNotNull(response, "no answer to: " + raw);
            assertEquals("close", response.header("Connection"));
            assertTrue(client.isClosedByServer());
            return response;
        }
    }

    @Test
    public void malformedRequestLineIs400() throws Exception {
        assertEquals(400, exchange("GARBAGE\r\nHost: x\r\n\r\n").code);
        assertEquals(400, exchange("GET / HTTP/1.1 junk\r\nHost: x\r\n\r\n").code);
    }

    @Test
    public void badPathIs400() throws Exception {
        assertEquals(400, exchange("GET /a/../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n").code);
        assertEquals(400, exchange("GET /a%zz HTTP/1.1\r\nHost: x\r\n\r\n").code);
        assertEquals(400, exchange("GET /?q=%zz HTTP/1.1\r\nHost: x\r\n\r\n").code);
    }

    @Test
    public void invalidContentLengthIs400() throws Exception {
        assertEquals(400, exchange("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n").code);
        assertEquals(400, exchange("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab").code);
    }

    @Test
    public void unsupportedTransferEncodingIs501() throws Exception {
        assertEquals(501, exchange("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip\r\n\r\n").code);
    }

    @Test
    public void malformedChunkReadByAHandlerIs400() throws Exception {
        assertEquals(400, exchange("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "+5\r\nhello\r\n0\r\n\r\n").code);
    }

    @Test
    public void wellFormedChunkedBodyStillWorks() throws Exception {
        try (TestServer server = new TestServer(router()).start();
             RawHttpClient client = server.connect()) {
            client.send("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
            RawHttpClient.Response response = client.readResponse();
            assertEquals(200, response.code);
            assertEquals("hello world", response.bodyText());
            //and the connection is still good for the next request
            assertEquals(200, client.get("/").code);
        }
    }
}
