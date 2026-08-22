package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.operations.DBOperationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DBFiltersTest {

    @Test
    public void missingFiltersParameterMeansNoFilters() throws Exception {
        DBFilters filters = DBFilters.parse(null);
        assertTrue(filters.isEmpty());
        assertEquals(0, filters.clauses().length);
        assertEquals(0, filters.args().length);
    }

    @Test
    public void parsesClausesAndArgs() throws Exception {
        DBFilters filters = DBFilters.parse("{\"clauses\":[\"name?\",\"id=\"],\"args\":[\"%foo%\",7]}");
        assertFalse(filters.isEmpty());
        assertArrayEquals(new String[]{"name?", "id="}, filters.clauses());
        assertArrayEquals(new Object[]{"%foo%", 7}, filters.args());
    }

    @Test
    public void emptyArraysAreAValidEmptyFilter() throws Exception {
        DBFilters filters = DBFilters.parse("{\"clauses\":[],\"args\":[]}");
        assertTrue(filters.isEmpty());
    }

    @Test
    public void argsMayOutnumberClauses() throws Exception {
        //an IN clause binds several arguments to one clause
        DBFilters filters = DBFilters.parse("{\"clauses\":[\"rowid\\u22083\"],\"args\":[1,2,3]}");
        assertEquals(1, filters.clauses().length);
        assertEquals(3, filters.args().length);
    }

    @Test
    public void malformedFiltersAreABadRequestRatherThanAServerError() {
        //these used to escape the handler as JSONException/IllegalArgumentException and reach the
        //client as a 500
        assertBadRequest("not json at all");
        assertBadRequest("{\"clauses\":[]}");
        assertBadRequest("{\"args\":[]}");
        assertBadRequest("{\"clauses\":\"nope\",\"args\":[]}");
        assertBadRequest("{\"clauses\":[],\"args\":\"nope\"}");
        assertBadRequest("{\"clauses\":[42],\"args\":[]}");
    }

    @Test
    public void roundTripsThroughItsWireShape() throws Exception {
        String json = "{\"clauses\":[\"id=\"],\"args\":[7]}";
        DBFilters filters = DBFilters.parse(json);
        //this is what the rights evaluator receives as the "filters" operation parameter
        DBFilters reparsed = DBFilters.of(filters.toJson());
        assertArrayEquals(filters.clauses(), reparsed.clauses());
        assertArrayEquals(filters.args(), reparsed.args());
    }

    @Test
    public void accessorsDoNotExposeInternalState() throws Exception {
        DBFilters filters = DBFilters.parse("{\"clauses\":[\"id=\"],\"args\":[7]}");
        filters.clauses()[0] = "tampered";
        filters.args()[0] = "tampered";
        assertArrayEquals(new String[]{"id="}, filters.clauses());
        assertArrayEquals(new Object[]{7}, filters.args());
    }

    @Test
    public void theEmptyInstanceIsShared() throws Exception {
        assertSame(DBFilters.EMPTY, DBFilters.parse(null));
    }

    private static void assertBadRequest(String json) {
        DBOperationException e = assertThrows(DBOperationException.class, () -> DBFilters.parse(json));
        assertEquals(DBOperationException.Kind.BAD_REQUEST, e.kind, "for input: " + json);
    }
}
