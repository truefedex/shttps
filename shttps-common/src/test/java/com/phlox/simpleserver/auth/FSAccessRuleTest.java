package com.phlox.simpleserver.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The {@code allow} column is an INTEGER. Both table implementations put that into JSON as a
 * {@link Long}, and {@code optBoolean} does not treat a number as true.
 */
public class FSAccessRuleTest {

    @Test
    public void integerAllowIsNotReadAsDeny() {
        JSONObject row = ruleRow(1L);
        FSAccessRule rule = FSAccessRule.deserialize(row);
        assertTrue(rule.allow);

        assertFalse(FSAccessRule.deserialize(ruleRow(0L)).allow);
    }

    @Test
    public void dbRuleReadsTheSameInteger() {
        JSONObject row = ruleRow(1L);
        assertTrue(DBAccessRule.deserialize(row).allow);
        assertFalse(DBAccessRule.deserialize(ruleRow(0L)).allow);
    }

    private static JSONObject ruleRow(long allow) {
        JSONObject row = new JSONObject();
        row.put(FSAccessRule.FIELD_ROLE_NAME, "editors");
        row.put(FSAccessRule.FIELD_SUBJECT, "/docs");
        row.put(FSAccessRule.FIELD_OPERATION, "DOWNLOAD");
        row.put(FSAccessRule.FIELD_ALLOW, allow);
        return row;
    }
}
