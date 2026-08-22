package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.FakeDatabaseOperations;
import com.phlox.simpleserver.database.FakeTableData;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.database.model.Table;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three reading operations: table pages, single cells and the schema.
 */
public class ReadOperationsTest {
    private TestDBEnvironment env;
    private FakeDatabaseOperations db;

    @BeforeEach
    public void setUp() {
        env = new TestDBEnvironment();
        db = new FakeDatabaseOperations();
    }

    // ---- ReadTableOperation ----

    @Test
    public void readTablePassesEveryParameterThrough() throws Exception {
        db.tableDataResult = FakeTableData.of(new String[]{"id"}, new Object[]{1});
        db.totalRowCount = 17L;

        TableDataResult result = readTable(new ReadTableOperation.Params("t", "a,b", 5L, 10L,
                "a", "DESC", true, true, false, DBFilters.parse("{\"clauses\":[\"id=\"],\"args\":[7]}")),
                TestDBEnvironment.userWith(User.DBRights.READ));

        assertEquals("getTableDataSecure:t:[a, b]:5:10:[id=]:[7]:a:true:true", db.calls.get(0));
        assertEquals(17L, result.total);
        assertSame(db.tableDataResult, result.data);
    }

    @Test
    public void readTableSortsAscendingUnlessTheOrderSaysDesc() throws Exception {
        readTable(paramsFor("t", null), TestDBEnvironment.userWith(User.DBRights.READ));
        assertTrue(db.calls.get(0).endsWith(":null:false:false"), db.calls.get(0));

        db.calls.clear();
        readTable(paramsFor("t", "desc"), TestDBEnvironment.userWith(User.DBRights.READ));
        assertTrue(db.calls.get(0).contains(":true:"), db.calls.get(0));
    }

    @Test
    public void readTableReportsNoTotalWhenNoneWasAskedFor() throws Exception {
        TableDataResult result = readTable(paramsFor("t", null), TestDBEnvironment.userWith(User.DBRights.READ));
        assertNull(result.total);
    }

    @Test
    public void readTableNeedsTheReadRight() {
        assertKind(DBOperationException.Kind.FORBIDDEN,
                () -> readTable(paramsFor("t", null), TestDBEnvironment.userWith(User.DBRights.READ_SCHEMA)));
        assertTrue(db.calls.isEmpty(), "no data may be read when the check failed, got " + db.calls);
    }

    @Test
    public void readTableRejectsAMissingTable() {
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> readTable(paramsFor(null, null), TestDBEnvironment.userWith(User.DBRights.READ)));
    }

    @Test
    public void readTableChecksRightsAndReadsThroughTheSameOperations() throws Exception {
        //both go to the DatabaseOperations of the enclosing transaction, so the rows returned are
        //the rows the check was made against
        env.config.setStoreUsersInDatabase(true);
        db.queryResult = FakeTableData.empty();
        readTable(paramsFor("t", null), TestDBEnvironment.userWith(User.DBRights.READ));
        assertTrue(db.calls.stream().anyMatch(c -> c.startsWith("query:")),
                "the rights lookup should have gone to the same operations, got " + db.calls);
        assertTrue(db.calls.get(db.calls.size() - 1).startsWith("getTableDataSecure:"));
    }

    // ---- ReadCellOperation ----

    @Test
    public void readCellPassesFiltersAsLists() throws Exception {
        db.cellResult = new Database.CellDataStreamInfo();
        db.cellResult.inputStream = new ByteArrayInputStream(new byte[]{1});
        Database.CellDataStreamInfo cell = readCell("t", "c",
                "{\"clauses\":[\"id=\"],\"args\":[7]}", TestDBEnvironment.userWith(User.DBRights.READ));
        assertSame(db.cellResult, cell);
        assertEquals("getSingleCellDataStream:t:c:[id=]:[7]", db.calls.get(0));
    }

    @Test
    public void readCellReportsAMissingRowAsNotFound() {
        db.cellResult = null;
        assertKind(DBOperationException.Kind.NOT_FOUND,
                () -> readCell("t", "c", null, TestDBEnvironment.userWith(User.DBRights.READ)));
    }

    @Test
    public void readCellReturnsAnEmptyCellRatherThanRefusingIt() throws Exception {
        //a null cell is a row that exists with nothing in it; the caller answers "no content"
        db.cellResult = new Database.CellDataStreamInfo();
        db.cellResult.type = "null";
        Database.CellDataStreamInfo cell = readCell("t", "c", null, TestDBEnvironment.userWith(User.DBRights.READ));
        assertNull(cell.inputStream);
    }

    @Test
    public void readCellNeedsTheReadRightAndAColumn() {
        assertKind(DBOperationException.Kind.FORBIDDEN,
                () -> readCell("t", "c", null, TestDBEnvironment.userWith(User.DBRights.UPDATE)));
        assertKind(DBOperationException.Kind.BAD_REQUEST,
                () -> readCell("t", null, null, TestDBEnvironment.userWith(User.DBRights.READ)));
    }

    // ---- ReadSchemaOperation ----

    @Test
    public void readSchemaReturnsEveryTableWhenNoneIsNamed() throws Exception {
        db.tables = new Table[]{table("a"), table("b")};
        Table[] tables = readSchema(null, TestDBEnvironment.userWith(User.DBRights.READ_SCHEMA));
        assertEquals(2, tables.length);
        assertEquals("getTables", db.calls.get(db.calls.size() - 1));
    }

    @Test
    public void readSchemaReturnsJustTheNamedTable() throws Exception {
        db.tables = new Table[]{table("a"), table("b")};
        Table[] tables = readSchema("b", TestDBEnvironment.userWith(User.DBRights.READ_SCHEMA));
        assertEquals(1, tables.length);
        assertEquals("b", tables[0].name);
    }

    @Test
    public void readSchemaReportsAnUnknownTableAsNotFound() {
        db.tables = new Table[]{table("a")};
        assertKind(DBOperationException.Kind.NOT_FOUND,
                () -> readSchema("nope", TestDBEnvironment.userWith(User.DBRights.READ_SCHEMA)));
    }

    @Test
    public void readSchemaNeedsTheReadSchemaRight() {
        assertKind(DBOperationException.Kind.FORBIDDEN,
                () -> readSchema(null, TestDBEnvironment.userWith(User.DBRights.READ)));
        assertTrue(db.calls.isEmpty(), "the schema may not be read when the check failed, got " + db.calls);
    }

    @Test
    public void readSchemaAllowsAnythingWhenAuthenticationIsOff() throws Exception {
        env.config.setAuthMode(SHTTPSConfig.AuthMode.NONE);
        db.tables = new Table[]{table("a")};
        assertEquals(1, readSchema(null, null).length);
    }

    // ---- helpers ----

    private static ReadTableOperation.Params paramsFor(String table, String sortDir) {
        return new ReadTableOperation.Params(table, null, null, null, null, sortDir,
                false, false, false, DBFilters.EMPTY);
    }

    private TableDataResult readTable(ReadTableOperation.Params params, User user) throws Exception {
        return new ReadTableOperation(env.config, env.authManager, params).execute(db, user);
    }

    private Database.CellDataStreamInfo readCell(String table, String column, String filters, User user) throws Exception {
        return new ReadCellOperation(env.config, env.authManager,
                ReadCellOperation.Params.parse(table, column, filters)).execute(db, user);
    }

    private Table[] readSchema(String table, User user) throws Exception {
        return new ReadSchemaOperation(env.config, env.authManager,
                new ReadSchemaOperation.Params(table)).execute(db, user);
    }

    private static Table table(String name) {
        Table t = new Table();
        t.name = name;
        return t;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertKind(DBOperationException.Kind kind, Action action) {
        DBOperationException e = assertThrows(DBOperationException.class, action::run);
        assertEquals(kind, e.kind);
    }
}
