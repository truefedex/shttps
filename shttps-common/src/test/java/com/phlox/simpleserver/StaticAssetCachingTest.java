package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The bundled web UI must be verifiable by a browser, which it was not: the only validator the
 * asset handler sent was a Last-Modified, and the platforms that keep their assets in a jar have
 * no date to put there, so every file went out stamped {@code Thu, 01 Jan 1970}. A response with
 * no expiry and no Cache-Control is then cached by the heuristic in RFC 7234 - a tenth of its
 * apparent age, here over five years - and the browser stops asking. Updating the app left Chrome
 * running the page scripts of the version it first met against the new markup, which shows up as
 * a UI that is merely dead: the main menu button whose handler the stale script no longer
 * installs. A reinstall does not clear it and neither does restarting the server, because the
 * copy is the browser's.
 * <p>
 * So: a validator that is about the file rather than the clock, and an instruction to use it.
 */
@Timeout(30)
public class StaticAssetCachingTest {
    private static final String ASSET = "/shttps-static-public/common.js";

    private LiveServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    private String body(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + server.port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private String get(String path, String extraHeader) {
        return "GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                (extraHeader == null ? "" : extraHeader + "\r\n") + "\r\n";
    }

    @Test
    public void anAssetCarriesAnETagAndAskedToBeRevalidated(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> { });

        String head = server.exchangeHead(get(ASSET, null), new byte[0]);
        assertTrue(head.startsWith("HTTP/1.1 200"), head);

        assertEquals("no-cache", LiveServer.header(head, "Cache-Control"),
                "without this the browser is free to never ask for the file again");
        String etag = LiveServer.header(head, "ETag");
        assertNotNull(etag, "the asset needs a validator that changes when the file does");
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag);

        assertNull(LiveServer.header(head, "Last-Modified"),
                "a platform that cannot date its assets must send no date at all, " +
                        "never the epoch - that is what buys the browser five years of silence");
    }

    @Test
    public void aBundledPageNamesItsScriptsWithTheBuildToken(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> { });

        String page = body("/shttps-static-public/status/index.html");

        assertFalse(page.contains("{{v}}"),
                "the placeholder has to be filled in, or every script URL on the page is broken");
        String version = SHTTPSApp.getInstance().getWebAssetsVersion();
        assertTrue(page.contains("/shttps-static-public/common.js?v=" + version), page);
        assertTrue(page.contains("/shttps-static-public/status/status.js?v=" + version), page);
    }

    @Test
    public void anUnchangedAssetIsAnswered304(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> { });

        String etag = LiveServer.header(server.exchangeHead(get(ASSET, null), new byte[0]), "ETag");

        String revalidated = server.exchangeHead(get(ASSET, "If-None-Match: " + etag), new byte[0]);
        assertTrue(revalidated.startsWith("HTTP/1.1 304"), revalidated);
        assertEquals(etag, LiveServer.header(revalidated, "ETag"), revalidated);

        //a browser holding a copy of some older build gets the file, not its own copy back
        String stale = server.exchangeHead(get(ASSET, "If-None-Match: \"deadbeef\""), new byte[0]);
        assertTrue(stale.startsWith("HTTP/1.1 200"), stale);
    }
}
