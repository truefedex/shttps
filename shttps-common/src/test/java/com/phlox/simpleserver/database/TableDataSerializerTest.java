package com.phlox.simpleserver.database;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TableDataSerializerTest {

    private static FakeTableData threeRows() {
        return FakeTableData.of(new String[]{"id", "name"},
                new Object[]{1, "a"},
                new Object[]{2, "b"},
                new Object[]{3, "c"});
    }

    @Test
    public void streamsRowsAsPositionalArraysByDefault() throws Exception {
        String json = stream(threeRows(), new TableDataSerializer.Options());
        assertEquals("{\"data\":[[1,\"a\"],[2,\"b\"],[3,\"c\"]]}", json);
    }

    @Test
    public void streamsRowsAsObjectsWhenAsked() throws Exception {
        String json = stream(threeRows(), new TableDataSerializer.Options().rowsAsObjects(true));
        //this is the shape /api/db/table answers with when rowsAsObjects is set
        assertTrue(json.contains("\"id\":1"), json);
        assertTrue(json.contains("\"name\":\"a\""), json);
    }

    @Test
    public void emitsTotalWhenPresentAndOmitsItOtherwise() throws Exception {
        assertTrue(stream(threeRows(), new TableDataSerializer.Options().total(42L))
                .startsWith("{\"total\":42,\"data\":["));
        assertTrue(stream(threeRows(), new TableDataSerializer.Options())
                .startsWith("{\"data\":["));
    }

    @Test
    public void appliesOffsetAndLimitAndReportsThem() throws Exception {
        //this is how /api/db/query pages: the statement runs whole, the serializer takes a slice
        String json = stream(threeRows(), new TableDataSerializer.Options().paging(1, 1));
        assertEquals("{\"offset\":1,\"limit\":1,\"data\":[[2,\"b\"]]}", json);
    }

    @Test
    public void anOffsetPastTheEndYieldsNoRows() throws Exception {
        String json = stream(threeRows(), new TableDataSerializer.Options().paging(10, 5));
        assertEquals("{\"offset\":10,\"limit\":5,\"data\":[]}", json);
    }

    @Test
    public void emitsColumnNamesWhenAsked() throws Exception {
        String json = stream(threeRows(), new TableDataSerializer.Options().includeColumnNames(true));
        assertTrue(json.startsWith("{\"columns\":[\"id\",\"name\"],\"data\":["), json);
    }

    @Test
    public void materializeProducesTheSameEnvelope() throws Exception {
        TableDataSerializer.Options options = new TableDataSerializer.Options()
                .paging(1, 1).total(42L).includeColumnNames(true);
        JSONObject materialized = TableDataSerializer.materialize(threeRows(), options);
        //same fields and same rows as the streaming path, just built in memory
        assertEquals(42L, materialized.getLong("total"));
        assertEquals(1, materialized.getInt("offset"));
        assertEquals(1, materialized.getInt("limit"));
        assertEquals("[\"id\",\"name\"]", materialized.getJSONArray("columns").toString());
        assertEquals("[[2,\"b\"]]", materialized.getJSONArray("data").toString());
    }

    @Test
    public void bothEntryPointsCloseTheCursor() throws Exception {
        FakeTableData streamed = threeRows();
        stream(streamed, new TableDataSerializer.Options());
        assertTrue(streamed.isClosed(), "streamTo must close the cursor");

        FakeTableData materialized = threeRows();
        TableDataSerializer.materialize(materialized, new TableDataSerializer.Options());
        assertTrue(materialized.isClosed(), "materialize must close the cursor");
    }

    @Test
    public void bothEntryPointsCloseTheCursorWhenReadingFails() {
        //an unclosed cursor keeps a database connection open for good
        FakeTableData streamed = threeRows().failAfter(2, new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class,
                () -> stream(streamed, new TableDataSerializer.Options()));
        assertTrue(streamed.isClosed(), "streamTo must close the cursor after a failure");

        FakeTableData materialized = threeRows().failAfter(2, new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class,
                () -> TableDataSerializer.materialize(materialized, new TableDataSerializer.Options()));
        assertTrue(materialized.isClosed(), "materialize must close the cursor after a failure");
    }

    private static String stream(FakeTableData data, TableDataSerializer.Options options) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TableDataSerializer.streamTo(out, data, options);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
