package com.phlox.simpleserver.test;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseMigrator;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.database.operations.DeleteOperation;
import com.phlox.simpleserver.database.operations.UpdateOperation;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Access rules whose decision is an SQL expression written by the user.
 * <p>
 * These are the one part of rights evaluation a fake database can not stand in for: the expression
 * is real SQL, run against the real database, with the operation's parameters bound to it. It is
 * also the part that pins down what an operation puts into those parameters -
 * {@code :param.filters} and {@code :param.values} are a contract with whoever wrote the rule.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DBAccessRuleExpressionTest {
    private static final String ROLE = "restricted";

    private Database db;

    @BeforeEach
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("dbrules").toFile();
        File dbFile = new File(dir, "test.db");
        db = new DatabaseFabricImpl().openDatabase(dbFile.getAbsolutePath());
        //creates user/user_role/shttps_db_access_rule
        DatabaseMigrator.runMigrations(db, true);
        db.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, owner TEXT, body TEXT)");
        db.execute("INSERT INTO notes (id, owner, body) VALUES (1, 'tester', 'hello')");
        //a role with no general database rights at all, so only the rule can allow anything
        db.execute("INSERT INTO user_role (name, fs_rights, db_rights, system_rights) VALUES " +
                "('" + ROLE + "', 0, 0, 0)");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private void addRule(String operation, String expression) throws Exception {
        db.execute("INSERT INTO shttps_db_access_rule (role_name, subject, operation, allow, expression) " +
                "VALUES ('" + ROLE + "', 'notes', '" + operation + "', 1, '" + expression + "')");
    }

    private User restrictedUser() {
        User user = new User("tester", "", null,
                EnumSet.noneOf(User.FileSystemRights.class),
                EnumSet.noneOf(User.DBRights.class), ROLE,
                0L, null, null, EnumSet.noneOf(User.SystemRights.class), 0L);
        return user;
    }

    private int update(String filtersJson) throws Exception {
        TestConfig config = new TestConfig();
        return db.runTransaction(ops -> new UpdateOperation(config, new TestAuthManager(),
                UpdateOperation.Params.parse("notes", "{\"body\":\"changed\"}", filtersJson))
                .execute(ops, restrictedUser()));
    }

    private int delete(String filtersJson) throws Exception {
        TestConfig config = new TestConfig();
        return db.runTransaction(ops -> new DeleteOperation(config, new TestAuthManager(),
                DeleteOperation.Params.parse("notes", filtersJson))
                .execute(ops, restrictedUser()));
    }

    /**
     * A rule that inspects the filters the client sent. The expression sees them as the same JSON
     * envelope the client used, so {@code json_extract} on it has to keep working.
     */
    @Test
    public void anExpressionCanDecideOnTheFiltersOfTheRequest() throws Exception {
        //allow only when the filters name the id column
        addRule(UpdateOperation.OPERATION,
                "SELECT json_extract(:param.filters, ''$.clauses[0]'') = ''id=''");

        //refused first, while the row still holds its original value, so that "unchanged" means
        //something
        DBOperationException refused = assertThrows(DBOperationException.class,
                () -> update("{\"clauses\":[\"owner=\"],\"args\":[\"tester\"]}"));
        assertEquals(DBOperationException.Kind.FORBIDDEN, refused.kind);
        assertEquals("hello", bodyOfNote1(), "the refused update must not have happened");

        assertEquals(1, update("{\"clauses\":[\"id=\"],\"args\":[1]}"),
                "a request whose filters satisfy the rule should go through");
        assertEquals("changed", bodyOfNote1());
    }

    /** The same for the values of an update, which reach the expression as :param.values. */
    @Test
    public void anExpressionCanDecideOnTheValuesOfTheRequest() throws Exception {
        addRule(UpdateOperation.OPERATION,
                "SELECT json_extract(:param.values, ''$.body'') IS NOT NULL");
        assertEquals(1, update("{\"clauses\":[\"id=\"],\"args\":[1]}"));
        assertEquals("changed", bodyOfNote1());
    }

    /** And for a delete, whose only parameter is the filters. */
    @Test
    public void aDeleteRuleSeesTheFiltersToo() throws Exception {
        addRule(DeleteOperation.OPERATION,
                "SELECT json_array_length(json_extract(:param.filters, ''$.args'')) = 1");

        DBOperationException refused = assertThrows(DBOperationException.class,
                () -> delete("{\"clauses\":[\"id\\u22082\"],\"args\":[1,2]}"));
        assertEquals(DBOperationException.Kind.FORBIDDEN, refused.kind);

        assertEquals(1, delete("{\"clauses\":[\"id=\"],\"args\":[1]}"));
    }

    /** Non-expression rules still decide by their allow flag alone. */
    @Test
    public void aRuleWithoutAnExpressionAllowsOrDeniesOutright() throws Exception {
        db.execute("INSERT INTO shttps_db_access_rule (role_name, subject, operation, allow, expression) " +
                "VALUES ('" + ROLE + "', 'notes', '" + UpdateOperation.OPERATION + "', 1, NULL)");
        assertEquals(1, update("{\"clauses\":[\"id=\"],\"args\":[1]}"));
    }

    /** With no rule at all the role's own rights decide, and this role has none. */
    @Test
    public void withoutAnyRuleTheRoleRightsDecide() {
        DBOperationException refused = assertThrows(DBOperationException.class,
                () -> update("{\"clauses\":[\"id=\"],\"args\":[1]}"));
        assertEquals(DBOperationException.Kind.FORBIDDEN, refused.kind);
    }

    /** The filters an expression sees round-trip through DBFilters unchanged. */
    @Test
    public void theFiltersTheRuleSeesAreTheOnesTheClientSent() throws Exception {
        DBFilters filters = DBFilters.parse("{\"clauses\":[\"id=\"],\"args\":[1]}");
        assertEquals("[\"id=\"]", filters.toJson().getJSONArray("clauses").toString());
        assertEquals("[1]", filters.toJson().getJSONArray("args").toString());
    }

    private String bodyOfNote1() throws Exception {
        try (TableData data = db.query("SELECT body FROM notes WHERE id=1", null, false)) {
            return data.next() ? data.getString(0) : null;
        }
    }

    /** Authentication is on and users live in the database, so rules are consulted. */
    private static class TestConfig implements SHTTPSConfig {
        private final Map<String, Object> values = new HashMap<>();

        TestConfig() {
            setAuthMode(AuthMode.BASIC_AUTH);
            setStoreUsersInDatabase(true);
            setAllowDatabaseTableDataEditingApi(true);
        }

        @Override public int getInt(String key, int defaultValue) {
            Object v = values.get(key); return v == null ? defaultValue : (Integer) v;
        }
        @Override public void setInt(String key, int value) { values.put(key, value); }
        @Override public boolean getBoolean(String key, boolean defaultValue) {
            Object v = values.get(key); return v == null ? defaultValue : (Boolean) v;
        }
        @Override public void setBoolean(String key, boolean value) { values.put(key, value); }
        @Override public String getString(String key, String defaultValue) {
            Object v = values.get(key); return v == null ? defaultValue : (String) v;
        }
        @Override public void setString(String key, String value) { values.put(key, value); }
        @Override public JSONArray getJsonArray(String key, JSONArray defaultValue) {
            Object v = values.get(key); return v == null ? defaultValue : (JSONArray) v;
        }
        @Override public void setJSONArray(String key, JSONArray value) { values.put(key, value); }
        @Override public DocumentFile getRootDir() { return null; }
        @Override public void setRootDir(String value) { }
        @Override public KeyStore getTLSCert() { return null; }
        @Override public void setTLSCert(byte[] value) { }
    }

    private class TestAuthManager implements AuthManager {
        //the evaluator only uses the holder for its loadRole(String) overload, which the
        //operations never take - they pass the transaction's own operations instead
        private final UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(db));

        @Override public @Nullable User getAuthenticatedUser(@NotNull RequestContext context) { return null; }
        @Override public @Nullable User authenticate(RequestContext context, Request request) { return null; }
        @Override public void logout(@NotNull RequestContext context, @NotNull Request request) { }
        @Override public @NotNull UserRightsEvaluator getUserRightsEvaluator() { return evaluator; }
    }
}
