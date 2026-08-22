package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;

import org.junit.jupiter.api.Test;

/**
 * The screen stream endpoint is the most sensitive one in the app, and a WebSocket handshake -
 * unlike an XHR - is not blocked by the browser's same origin policy. These lock down the check
 * that stops a page on another origin from opening an authenticated connection with the user's
 * cookies and watching the screen.
 */
public class ScreenStreamOriginCheckTest {
    //the origin check runs before anything is looked up in the config or the auth manager
    private final ScreenStreamWebSocketHandler handler =
            new ScreenStreamWebSocketHandler(null, null, () -> null, () -> null);

    @Test
    public void clientsThatAreNotBrowsersAreAllowed() {
        //no Origin header at all: a native client, a script, curl
        assertTrue(isAllowed("192.168.1.5:8080", null));
        assertTrue(isAllowed("192.168.1.5:8080", ""));
    }

    @Test
    public void sameOriginIsAllowed() {
        assertTrue(isAllowed("192.168.1.5:8080", "http://192.168.1.5:8080"));
        assertTrue(isAllowed("192.168.1.5:8443", "https://192.168.1.5:8443"));
        assertTrue(isAllowed("phone.local", "http://phone.local"));
        //the Host header is compared case insensitively, host names are
        assertTrue(isAllowed("Phone.Local", "http://phone.local"));
        assertTrue(isAllowed(" 192.168.1.5:8080 ", "http://192.168.1.5:8080"));
    }

    @Test
    public void foreignOriginIsRejected() {
        assertFalse(isAllowed("192.168.1.5:8080", "http://evil.example.com"));
        assertFalse(isAllowed("192.168.1.5:8080", "https://evil.example.com"));
        //a different port is a different origin, and another app on the device could listen there
        assertFalse(isAllowed("192.168.1.5:8080", "http://192.168.1.5:9090"));
        //a host that merely starts or ends with ours must not pass either
        assertFalse(isAllowed("192.168.1.5:8080", "http://192.168.1.50:8080"));
        assertFalse(isAllowed("phone.local", "http://notphone.local"));
    }

    @Test
    public void malformedOrJavascriptOriginsAreRejected() {
        //browsers send "null" for sandboxed iframes and file:// pages
        assertFalse(isAllowed("192.168.1.5:8080", "null"));
        assertFalse(isAllowed("192.168.1.5:8080", "192.168.1.5:8080"));
        //no Host header to compare against
        assertFalse(isAllowed(null, "http://192.168.1.5:8080"));
    }

    private boolean isAllowed(String host, String origin) {
        Request request = new Request();
        if (host != null) {
            request.headers.add(Request.HEADER_HOST, host);
        }
        if (origin != null) {
            request.headers.add(Request.HEADER_ORIGIN, origin);
        }
        return handler.isOriginAllowed(request, origin);
    }
}
