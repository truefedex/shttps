package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class SqlStatementSplitterTest {

    @Test
    public void splitsSimpleStatements() {
        List<String> result = SqlStatementSplitter.split(
                "SELECT 1; INSERT INTO t VALUES (2); DELETE FROM t");
        assertEquals(Arrays.asList(
                "SELECT 1",
                "INSERT INTO t VALUES (2)",
                "DELETE FROM t"), result);
    }

    @Test
    public void singleStatementWithoutSemicolon() {
        assertEquals(Arrays.asList("SELECT * FROM t"),
                SqlStatementSplitter.split("SELECT * FROM t"));
    }

    @Test
    public void singleStatementWithTrailingSemicolon() {
        assertEquals(Arrays.asList("SELECT * FROM t"),
                SqlStatementSplitter.split("SELECT * FROM t;"));
    }

    @Test
    public void emptyAndWhitespaceOnlyInput() {
        assertTrue(SqlStatementSplitter.split("").isEmpty());
        assertTrue(SqlStatementSplitter.split("   \n\t  ").isEmpty());
        assertTrue(SqlStatementSplitter.split(";;;  ;").isEmpty());
    }

    @Test
    public void commentOnlyInputProducesNoStatements() {
        assertTrue(SqlStatementSplitter.split("-- just a comment\n").isEmpty());
        assertTrue(SqlStatementSplitter.split("/* nothing ; here */").isEmpty());
    }

    @Test
    public void trailingCommentAfterLastStatementIsDropped() {
        assertEquals(Arrays.asList("SELECT 1"),
                SqlStatementSplitter.split("SELECT 1; -- done\n"));
    }

    @Test
    public void semicolonInsideSingleQuotedString() {
        assertEquals(Arrays.asList("SELECT 'a;b'", "SELECT 2"),
                SqlStatementSplitter.split("SELECT 'a;b'; SELECT 2;"));
    }

    @Test
    public void escapedQuoteInsideString() {
        assertEquals(Arrays.asList("SELECT 'it''s; fine'", "SELECT 2"),
                SqlStatementSplitter.split("SELECT 'it''s; fine'; SELECT 2"));
    }

    @Test
    public void semicolonInsideDoubleQuotedIdentifier() {
        assertEquals(Arrays.asList("SELECT \"col;name\" FROM t", "SELECT 2"),
                SqlStatementSplitter.split("SELECT \"col;name\" FROM t; SELECT 2"));
    }

    @Test
    public void semicolonInsideBacktickAndBracketIdentifiers() {
        assertEquals(Arrays.asList("SELECT `a;b` FROM t", "SELECT [c;d] FROM t"),
                SqlStatementSplitter.split("SELECT `a;b` FROM t; SELECT [c;d] FROM t;"));
    }

    @Test
    public void semicolonInsideLineComment() {
        assertEquals(Arrays.asList("SELECT 1 -- comment; not a separator\n, 2", "SELECT 3"),
                SqlStatementSplitter.split(
                        "SELECT 1 -- comment; not a separator\n, 2; SELECT 3"));
    }

    @Test
    public void semicolonInsideBlockComment() {
        assertEquals(Arrays.asList("SELECT /* ; ; ; */ 1", "SELECT 2"),
                SqlStatementSplitter.split("SELECT /* ; ; ; */ 1; SELECT 2"));
    }

    @Test
    public void keepsTriggerBodyIntact() {
        String trigger = "CREATE TRIGGER auto_timestamp_after_insert\n" +
                "AFTER INSERT ON payment\n" +
                "FOR EACH ROW\n" +
                "WHEN NEW.created_at IS NULL\n" +
                "BEGIN\n" +
                "UPDATE payment\n" +
                "SET created_at = CURRENT_TIMESTAMP\n" +
                "WHERE rowid = NEW.rowid;\n" +
                "END";
        List<String> result = SqlStatementSplitter.split(trigger + ";");
        assertEquals(Arrays.asList(trigger), result);
    }

    @Test
    public void triggerFollowedByOtherStatements() {
        String sql = "CREATE TABLE payment(id INTEGER, created_at TEXT);\n" +
                "CREATE TRIGGER trg AFTER INSERT ON payment BEGIN\n" +
                "  UPDATE payment SET created_at = CURRENT_TIMESTAMP WHERE rowid = NEW.rowid;\n" +
                "  DELETE FROM audit WHERE age > 10;\n" +
                "END;\n" +
                "SELECT * FROM payment;";
        List<String> result = SqlStatementSplitter.split(sql);
        assertEquals(3, result.size());
        assertEquals("CREATE TABLE payment(id INTEGER, created_at TEXT)", result.get(0));
        assertTrue(result.get(1).startsWith("CREATE TRIGGER trg"));
        assertTrue(result.get(1).endsWith("END"));
        assertTrue(result.get(1).contains("DELETE FROM audit WHERE age > 10;"));
        assertEquals("SELECT * FROM payment", result.get(2));
    }

    @Test
    public void tempTriggerIsRecognized() {
        String trigger = "CREATE TEMP TRIGGER trg AFTER INSERT ON t BEGIN\n" +
                "SELECT 1;\nEND";
        assertEquals(Arrays.asList(trigger, "SELECT 2"),
                SqlStatementSplitter.split(trigger + ";\nSELECT 2;"));

        String trigger2 = trigger.replace("TEMP", "TEMPORARY");
        assertEquals(Arrays.asList(trigger2, "SELECT 2"),
                SqlStatementSplitter.split(trigger2 + ";\nSELECT 2;"));
    }

    @Test
    public void triggerKeywordsAreCaseInsensitive() {
        String trigger = "create trigger trg after insert on t begin\n" +
                "update t set x = 1;\nend";
        assertEquals(Arrays.asList(trigger),
                SqlStatementSplitter.split(trigger + ";"));
    }

    @Test
    public void caseEndInsideTriggerBodyDoesNotTerminateTrigger() {
        String trigger = "CREATE TRIGGER trg AFTER INSERT ON t BEGIN\n" +
                "UPDATE t SET x = CASE WHEN NEW.a > 0 THEN 1 ELSE 2 END WHERE id = NEW.id;\n" +
                "SELECT CASE WHEN 1 THEN 'a' ELSE 'b' END;\n" +
                "END";
        assertEquals(Arrays.asList(trigger, "SELECT 3"),
                SqlStatementSplitter.split(trigger + "; SELECT 3;"));
    }

    @Test
    public void caseEndOutsideTriggerIsJustAnExpression() {
        assertEquals(Arrays.asList(
                        "SELECT CASE WHEN a THEN 1 ELSE 2 END FROM t",
                        "SELECT 2"),
                SqlStatementSplitter.split(
                        "SELECT CASE WHEN a THEN 1 ELSE 2 END FROM t; SELECT 2"));
    }

    @Test
    public void identifierStartingWithTriggerIsNotATrigger() {
        // "trigger_log" must not be confused with the TRIGGER keyword
        assertEquals(Arrays.asList(
                        "CREATE TABLE trigger_log(id INTEGER)",
                        "SELECT 2"),
                SqlStatementSplitter.split(
                        "CREATE TABLE trigger_log(id INTEGER); SELECT 2;"));
    }

    @Test
    public void transactionBeginIsNotATriggerBody() {
        assertEquals(Arrays.asList("BEGIN", "UPDATE t SET a = 1", "COMMIT"),
                SqlStatementSplitter.split("BEGIN; UPDATE t SET a = 1; COMMIT;"));
    }

    @Test
    public void commentsInsideTriggerBodyArePreserved() {
        String trigger = "CREATE TRIGGER trg AFTER INSERT ON t BEGIN\n" +
                "-- set the timestamp; important\n" +
                "UPDATE t SET ts = CURRENT_TIMESTAMP;\n" +
                "END";
        assertEquals(Arrays.asList(trigger, "SELECT 1"),
                SqlStatementSplitter.split(trigger + ";\nSELECT 1"));
    }

    @Test
    public void unterminatedStringConsumesRestOfInput() {
        // best effort: no crash, the rest of the input belongs to the last statement
        assertEquals(Arrays.asList("SELECT 'unterminated; SELECT 2"),
                SqlStatementSplitter.split("SELECT 'unterminated; SELECT 2"));
    }

    @Test
    public void explainStatementsSplitNormally() {
        assertEquals(Arrays.asList("EXPLAIN QUERY PLAN SELECT * FROM t", "SELECT 1"),
                SqlStatementSplitter.split("EXPLAIN QUERY PLAN SELECT * FROM t; SELECT 1;"));
    }

    @Test
    public void explainCreateTriggerKeepsBodyIntact() {
        String stmt = "EXPLAIN CREATE TRIGGER trg AFTER INSERT ON t BEGIN\n" +
                "SELECT 1;\nEND";
        assertEquals(Arrays.asList(stmt, "SELECT 2"),
                SqlStatementSplitter.split(stmt + "; SELECT 2"));
    }
}
