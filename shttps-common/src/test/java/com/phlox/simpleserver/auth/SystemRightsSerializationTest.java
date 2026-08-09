package com.phlox.simpleserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Rights are stored as a bitmask of enum ordinals, both for users and for roles, which makes the
 * order of the constants part of the persisted format: inserting one in the middle would silently
 * hand every stored user a different set of rights than the one they were given.
 */
public class SystemRightsSerializationTest {

    @Test
    public void systemRightsKeepTheirPositions() {
        assertEquals(0, User.SystemRights.READ_STATUS.ordinal());
        assertEquals(1, User.SystemRights.EXECUTE_HANDLER.ordinal());
        assertEquals(2, User.SystemRights.VIEW_SCREEN.ordinal());
        assertEquals(3, User.SystemRights.CONTROL_SCREEN.ordinal());
    }

    @Test
    public void userScreenRightsSurviveARoundTrip() {
        User user = new User("bob", "hash");
        user.systemRights = EnumSet.of(User.SystemRights.READ_STATUS,
                User.SystemRights.VIEW_SCREEN, User.SystemRights.CONTROL_SCREEN);

        JSONObject serialized = user.serialize();
        //READ_STATUS | VIEW_SCREEN | CONTROL_SCREEN = 1 + 4 + 8
        assertEquals(13, serialized.getInt(User.FIELD_SYSTEM_RIGHTS));

        User restored = User.deserialize(serialized);
        assertEquals(user.systemRights, restored.systemRights);
    }

    @Test
    public void roleScreenRightsSurviveARoundTrip() {
        UserRole role = new UserRole("watchers",
                EnumSet.noneOf(User.FileSystemRights.class),
                EnumSet.noneOf(User.DBRights.class),
                null,
                EnumSet.of(User.SystemRights.VIEW_SCREEN));

        UserRole restored = UserRole.deserialize(role.serialize());
        assertEquals(EnumSet.of(User.SystemRights.VIEW_SCREEN), restored.systemRights);
    }

    @Test
    public void usersStoredBeforeScreenRightsExistedGetNone() {
        //a user written by an older version: only the two rights that existed back then
        JSONObject stored = new JSONObject();
        stored.put(User.FIELD_IDENTITY, "bob");
        stored.put(User.FIELD_PASSWORD, "hash");
        stored.put(User.FIELD_SYSTEM_RIGHTS, 3);

        User restored = User.deserialize(stored);
        assertTrue(restored.systemRights.contains(User.SystemRights.READ_STATUS));
        assertTrue(restored.systemRights.contains(User.SystemRights.EXECUTE_HANDLER));
        assertFalse(restored.systemRights.contains(User.SystemRights.VIEW_SCREEN));
        assertFalse(restored.systemRights.contains(User.SystemRights.CONTROL_SCREEN));
    }
}
