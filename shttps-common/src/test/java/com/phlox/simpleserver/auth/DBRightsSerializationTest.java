package com.phlox.simpleserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * The same guard {@link SystemRightsSerializationTest} gives the system rights, for the database
 * ones: they are stored as a bitmask of enum ordinals in the {@code db_rights} column of both the
 * user and the role table, so the order of the constants is part of the persisted format. Inserting
 * one in the middle would silently hand every stored user and role a different set of rights than
 * the one they were given.
 */
public class DBRightsSerializationTest {

    @Test
    public void dbRightsKeepTheirPositions() {
        assertEquals(0, User.DBRights.CREATE.ordinal());
        assertEquals(1, User.DBRights.READ.ordinal());
        assertEquals(2, User.DBRights.UPDATE.ordinal());
        assertEquals(3, User.DBRights.DELETE.ordinal());
        assertEquals(4, User.DBRights.READ_SCHEMA.ordinal());
        assertEquals(5, User.DBRights.EXEC_SQL.ordinal());
        assertEquals(6, User.DBRights.USE_TRANSACTION.ordinal());
        //if this fails because a right was added, append it and extend this test - do not renumber
        assertEquals(7, User.DBRights.values().length);
    }

    @Test
    public void userDBRightsSurviveARoundTrip() {
        User user = new User("bob", "hash");
        user.dbRights = EnumSet.of(User.DBRights.READ, User.DBRights.EXEC_SQL,
                User.DBRights.USE_TRANSACTION);

        JSONObject serialized = user.serialize();
        //READ | EXEC_SQL | USE_TRANSACTION = 2 + 32 + 64
        assertEquals(98, serialized.getInt(User.FIELD_DB_RIGHTS));

        User restored = User.deserialize(serialized);
        assertEquals(user.dbRights, restored.dbRights);
    }

    @Test
    public void roleDBRightsSurviveARoundTrip() {
        UserRole role = new UserRole("editors",
                EnumSet.noneOf(User.FileSystemRights.class),
                EnumSet.of(User.DBRights.READ, User.DBRights.USE_TRANSACTION),
                null,
                EnumSet.noneOf(User.SystemRights.class));

        UserRole restored = UserRole.deserialize(role.serialize());
        assertEquals(EnumSet.of(User.DBRights.READ, User.DBRights.USE_TRANSACTION),
                restored.dbRights);
    }

    /**
     * The reason appending is safe: a record written before the right existed simply lacks its bit,
     * so the right is denied rather than accidentally granted.
     */
    @Test
    public void usersStoredBeforeTheTransactionRightExistedDoNotGetIt() {
        JSONObject stored = new JSONObject();
        stored.put(User.FIELD_IDENTITY, "bob");
        stored.put(User.FIELD_PASSWORD, "hash");
        //every right that existed before USE_TRANSACTION was appended: bits 0..5
        stored.put(User.FIELD_DB_RIGHTS, 63);

        User restored = User.deserialize(stored);
        assertTrue(restored.dbRights.contains(User.DBRights.CREATE));
        assertTrue(restored.dbRights.contains(User.DBRights.EXEC_SQL));
        assertFalse(restored.dbRights.contains(User.DBRights.USE_TRANSACTION));
    }
}
