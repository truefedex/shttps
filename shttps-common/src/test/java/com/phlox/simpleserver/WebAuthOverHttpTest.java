package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.Utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * The web login over a real socket: what an anonymous request gets, the session cookie a login
 * hands out, that the cookie is what grants access and that logout takes it back, and that the
 * brute force backoff answers 429 - it used to escape as an exception and reach the client as a
 * 500 Internal Server Error, for Basic auth on every authenticated request during the backoff.
 */
@Timeout(30)
public class WebAuthOverHttpTest {
    private static final String PASSWORD = "secret";
    private LiveServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    private void startWeb(Path temp) throws Exception {
        server = LiveServer.start(temp, c -> {
            c.setAuthMode(SHTTPSConfig.AuthMode.WEB);
            c.setUsers(List.of(user("alice")));
        });
    }

    @Test
    public void anAnonymousBrowserIsSentToTheLoginPageAndAnApiCallIsRefused(@TempDir Path temp) throws Exception {
        startWeb(temp);

        String page = server.exchangeHead(get("/", null), new byte[0]);
        assertTrue(page.startsWith("HTTP/1.1 302"), page);
        assertEquals("/shttps-static-public/auth/", LiveServer.header(page, "Location"));

        String api = server.exchange("GET /api/file/list?path=/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                "Content-Type: application/json\r\n\r\n", new byte[0]);
        assertTrue(api.contains(" 401 "), api);
    }

    @Test
    public void aLoginHandsOutAnHttpOnlySessionCookieThatLogoutTakesBack(@TempDir Path temp) throws Exception {
        startWeb(temp);

        String login = server.exchangeHead(loginRequest("alice", PASSWORD), new byte[0]);
        assertTrue(login.startsWith("HTTP/1.1 200"), login);
        String setCookie = LiveServer.header(login, "Set-Cookie");
        assertNotNull(setCookie, login);
        assertTrue(setCookie.startsWith("SESSION_ID="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        assertTrue(server.exchange(get("/api/file/list?path=/", cookie), new byte[0]).contains(" 200 "));

        String logout = server.exchange("POST /api/user/logout HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                "Cookie: " + cookie + "\r\nContent-Length: 0\r\n\r\n", new byte[0]);
        assertTrue(logout.contains(" 204 "), logout);

        //the same cookie is worth nothing after logout, even if the browser kept it
        assertSentToLogin(server.exchangeHead(get("/api/file/list?path=/", cookie), new byte[0]));
    }

    @Test
    public void aMadeUpSessionIdGrantsNothing(@TempDir Path temp) throws Exception {
        startWeb(temp);
        assertSentToLogin(server.exchangeHead(get("/api/file/list?path=/", "SESSION_ID=forged"), new byte[0]));
    }

    @Test
    public void aWrongPasswordIsRefusedAndTheBackoffAnswers429(@TempDir Path temp) throws Exception {
        startWeb(temp);

        String wrong = server.exchange(loginRequest("alice", "wrong"), new byte[0]);
        assertTrue(wrong.contains(" 401 "), wrong);

        //right away, even with the right password: the backoff is in force
        String blocked = server.exchangeHead(loginRequest("alice", PASSWORD), new byte[0]);
        assertTrue(blocked.startsWith("HTTP/1.1 429"), blocked);
        String retryAfter = LiveServer.header(blocked, "Retry-After");
        assertNotNull(retryAfter, blocked);
        assertTrue(Long.parseLong(retryAfter) >= 1, retryAfter);

        Thread.sleep(1100);
        String later = server.exchange(loginRequest("alice", PASSWORD), new byte[0]);
        assertTrue(later.contains(" 200 "), later);
    }

    @Test
    public void theBasicAuthBackoffIsA429NotAServerError(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> {
            c.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
            c.setUsers(List.of(user("alice")));
        });
        String wrong = server.exchange(basicGet("alice", "wrong"), new byte[0]);
        assertTrue(wrong.contains(" 401 "), wrong);

        String blocked = server.exchangeHead(basicGet("alice", PASSWORD), new byte[0]);
        assertTrue(blocked.startsWith("HTTP/1.1 429"), blocked);
        assertNotNull(LiveServer.header(blocked, "Retry-After"), blocked);
    }

    /** WebAuthMiddleware answers an anonymous GET without a Content-Type - page or API - with the login page. */
    private static void assertSentToLogin(String head) {
        assertTrue(head.startsWith("HTTP/1.1 302"), head);
        assertEquals("/shttps-static-public/auth/", LiveServer.header(head, "Location"));
    }

    private String loginRequest(String user, String password) {
        //the login form sends the FNV-1a hash of the password, not the password
        String body = "username=" + URLEncoder.encode(user, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(Utils.hashFNV1a32(password), StandardCharsets.UTF_8);
        return "POST /api/user/login HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }

    private static String get(String target, String cookie) {
        return "GET " + target + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                (cookie != null ? "Cookie: " + cookie + "\r\n" : "") + "\r\n";
    }

    private static String basicGet(String user, String password) {
        return "GET /api/file/list?path=/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                LiveServer.basicAuth(user, password) + "\r\n";
    }

    private static User user(String name) {
        return new User(name, Objects.requireNonNull(Utils.sha256(Utils.hashFNV1a32(PASSWORD))),
                null, EnumSet.allOf(User.FileSystemRights.class), EnumSet.noneOf(User.DBRights.class), null,
                System.currentTimeMillis(), null, null, EnumSet.noneOf(User.SystemRights.class), 0);
    }
}
