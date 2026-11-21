package com.phlox.simpleserver.handlers.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.DefaultRequestBodyReader;
import com.phlox.server.request.FixedLengthBodyStream;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.ScannerInputStream;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DatabaseTransactionScope;
import com.phlox.simpleserver.database.FakeTableData;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Access rules are set on folders and cover everything below them, while each handler asks about
 * the one path a request names. An operation over a whole tree used to ask only about its top: a
 * folder a rule kept from the user was deleted, zipped, copied, renamed or found by search
 * through the folder above it. The user here holds every right through the bitmask; the only
 * thing in the way is a deny rule on {@code /docs/private}.
 */
public class SubtreeRulesTest {
    private static final String ROLE = "staff";

    @TempDir
    Path temp;
    private Path root;
    private TestDBEnvironment.Config config;
    private AuthManager auth;
    private final List<Object[]> rules = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(temp.resolve("root"));
        Files.createDirectories(root.resolve("docs/private"));
        Files.createDirectories(root.resolve("out"));
        Files.writeString(root.resolve("docs/public.txt"), "public");
        Files.writeString(root.resolve("docs/private/secret.txt"), "secret");

        config = new TestDBEnvironment.Config() {
            @Override public DocumentFile getRootDir() { return DocumentFile.fromFile(root.toFile()); }
            @Override public void setRootDir(String value) { }
        };
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        config.setStoreUsersInDatabase(true);//rules are only consulted with users in the database
        config.setAllowEditing(true);

        User user = new User("u", "h", null, EnumSet.allOf(User.FileSystemRights.class),
                EnumSet.noneOf(User.DBRights.class), ROLE, 0L, null, null,
                EnumSet.noneOf(User.SystemRights.class), 0L);
        UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(rulesDatabase()));
        auth = new AuthManager() {
            @Override public User getAuthenticatedUser(RequestContext context) { return user; }
            @Override public User authenticate(RequestContext context, Request request) { return user; }
            @Override public void logout(RequestContext context, Request request) { }
            @Override public UserRightsEvaluator getUserRightsEvaluator() { return evaluator; }
        };
    }

    private void deny(String operation, String subject) {
        rules.add(new Object[]{ROLE, subject, operation, 0L, null});
    }

    @Test
    public void deletingAFolderDeniedByARuleIsRefused() throws Exception {
        deny(DeleteFileRequestHandler.DELETE_OPERATION, "/docs/private");
        DeleteFileRequestHandler handler = new DeleteFileRequestHandler(config, auth, store(), new LockManager());

        assertEquals(403, call(handler, "DELETE", "application/json",
                "{\"path\":\"/docs\",\"files\":[\"private\"]}").code);
        assertTrue(Files.exists(root.resolve("docs/private/secret.txt")));

        //and a folder above it takes the denied one along, so it is refused as well
        assertEquals(403, call(handler, "DELETE", "application/json",
                "{\"path\":\"/\",\"files\":[\"docs\"]}").code);
        assertTrue(Files.exists(root.resolve("docs/private/secret.txt")));

        //what the rule does not cover is unaffected
        assertEquals(204, call(handler, "DELETE", "application/json",
                "{\"path\":\"/docs\",\"files\":[\"public.txt\"]}").code);
        assertFalse(Files.exists(root.resolve("docs/public.txt")));
    }

    @Test
    public void aBatchWithOneRefusedItemDeletesNothing() throws Exception {
        deny(DeleteFileRequestHandler.DELETE_OPERATION, "/docs/private");
        DeleteFileRequestHandler handler = new DeleteFileRequestHandler(config, auth, store(), new LockManager());

        assertEquals(403, call(handler, "DELETE", "application/json",
                "{\"path\":\"/docs\",\"files\":[\"public.txt\",\"private\"]}").code);
        assertTrue(Files.exists(root.resolve("docs/public.txt")));
    }

    @Test
    public void zippingThroughTheParentDoesNotReadADeniedFolder() throws Exception {
        deny(ZipDownloadRequestHandler.ZIP_DOWNLOAD_OPERATION, "/docs/private");
        ZipDownloadRequestHandler handler = new ZipDownloadRequestHandler(config, auth, store(), new LockManager());

        assertEquals(403, call(handler, "POST", Request.CONTENT_TYPE_URL_ENCODED_FORM,
                form("path", "/", "files", "[\"docs\"]")).code);
        Response allowed = call(handler, "POST", Request.CONTENT_TYPE_URL_ENCODED_FORM,
                form("path", "/docs", "files", "[\"public.txt\"]"));
        assertEquals(200, allowed.code);
        //read it through: the archive is written by a thread of its own
        assertTrue(read(allowed.getStream()).length() > 0);
    }

    @Test
    public void copyingAFolderThatHoldsADeniedOneIsRefused() throws Exception {
        //a copy would put the secret somewhere this user can read
        deny(MoveFileRequestHandler.COPY_MOVE_OPERATION, "/docs/private");
        MoveFileRequestHandler handler = new MoveFileRequestHandler(config, auth, store(), new LockManager());

        assertEquals(403, call(handler, "POST", "application/json",
                "{\"path\":\"/out\",\"action\":\"copy\",\"files\":[\"/docs\"]}").code);
        assertFalse(Files.exists(root.resolve("out/docs")));
    }

    @Test
    public void renamingAFolderThatHoldsADeniedOneIsRefused() throws Exception {
        deny(RenameFileRequestHandler.RENAME_OPERATION, "/docs/private");
        RenameFileRequestHandler handler = new RenameFileRequestHandler(config, auth, store(), new LockManager());

        assertEquals(403, call(handler, "POST", Request.CONTENT_TYPE_URL_ENCODED_FORM,
                form("path", "/docs", "name", "docs2")).code);
        assertTrue(Files.exists(root.resolve("docs")));
    }

    @Test
    public void searchLeavesOutWhatIsInsideAFolderThatMayNotBeListed() throws Exception {
        deny(FileListRequestHandler.LIST_CONTENTS_OPERATION, "/docs/private");
        FileListRequestHandler handler = new FileListRequestHandler(config, auth, store(), new LockManager());

        Request request = request("GET", null, "");
        request.queryParams.put("path", "/");
        request.queryParams.put("search", "*.txt");
        Response response = handler.handleRequest(new RequestContext(new DefaultRequestBodyReader()), request);
        assertEquals(200, response.code);

        JSONArray found = new JSONArray(read(response.getStream()));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) names.add(found.getJSONObject(i).getString("name"));
        assertTrue(names.contains("public.txt"), names.toString());
        assertFalse(names.contains("secret.txt"), names.toString());
    }

    @Test
    public void renamingOntoAnExistingNameIsAConflictNotAnOverwrite() throws Exception {
        Files.writeString(root.resolve("docs/other.txt"), "other");
        RenameFileRequestHandler handler = new RenameFileRequestHandler(config, auth, store(), new LockManager());

        assertEquals(409, call(handler, "POST", Request.CONTENT_TYPE_URL_ENCODED_FORM,
                form("path", "/docs/public.txt", "name", "other.txt")).code);
        assertEquals("other", Files.readString(root.resolve("docs/other.txt")));
        assertEquals("public", Files.readString(root.resolve("docs/public.txt")));

        assertEquals(204, call(handler, "POST", Request.CONTENT_TYPE_URL_ENCODED_FORM,
                form("path", "/docs/public.txt", "name", "renamed.txt")).code);
        assertTrue(Files.exists(root.resolve("docs/renamed.txt")));
    }

    // ---------------------------------------------------------------- plumbing

    private static Response call(BaseFileRequestHandler handler, String method, String contentType,
                                 String body) throws Exception {
        return handler.handleRequest(new RequestContext(new DefaultRequestBodyReader()),
                request(method, contentType, body));
    }

    private static Request request(String method, String contentType, String body) {
        Request request = new Request();
        request.method = method;
        request.contentType = contentType;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.input = new ScannerInputStream(new ByteArrayInputStream(bytes));
        request.contentLength = bytes.length;
        request.bodyStream = new FixedLengthBodyStream(request.input, bytes.length);
        return request;
    }

    private static String form(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Answers the rules query the way SQLite would, filtering on role_name and operation. */
    private Database rulesDatabase() {
        DatabaseOperations ops = (DatabaseOperations) Proxy.newProxyInstance(
                DatabaseOperations.class.getClassLoader(), new Class<?>[]{DatabaseOperations.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName()) && ((String) args[0]).contains(com.phlox.simpleserver.auth.FSAccessRule.FS_ACCESS_RULES_TABLE_NAME)) {
                        Object[] params = (Object[]) args[1];
                        String role = (String) params[0];
                        String operation = (String) params[params.length - 1];
                        List<Object[]> rows = new ArrayList<>();
                        for (Object[] rule : rules) {
                            if (role.equals(rule[0]) && operation.equals(rule[2])) rows.add(rule);
                        }
                        return FakeTableData.of(new String[]{"role_name", "subject", "operation", "allow", "expression"},
                                rows.toArray(new Object[0][]));
                    }
                    //no roles stored: the bitmask on the user applies
                    return FakeTableData.empty("name");
                });
        return (Database) Proxy.newProxyInstance(Database.class.getClassLoader(), new Class<?>[]{Database.class},
                (proxy, method, args) -> {
                    if ("runTransaction".equals(method.getName())) {
                        return ((DatabaseTransactionScope<?>) args[0]).execute(ops);
                    }
                    if (method.getDeclaringClass() == DatabaseOperations.class) {
                        //outside a transaction the database answers the same queries itself
                        return method.invoke(ops, args);
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }

    private UserStore store() {
        return (UserStore) Proxy.newProxyInstance(UserStore.class.getClassLoader(),
                new Class<?>[]{UserStore.class}, (proxy, method, args) -> {
                    if ("provideUserRightsEvaluator".equals(method.getName())) {
                        return auth.getUserRightsEvaluator();
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }
}
