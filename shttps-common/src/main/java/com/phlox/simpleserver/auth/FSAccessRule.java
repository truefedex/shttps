package com.phlox.simpleserver.auth;

import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

public class FSAccessRule {
    public static final String FS_ACCESS_RULES_TABLE_NAME = "shttps_fs_access_rule";
    public static final String FIELD_ROLE_NAME = "role_name";
    public static final String FIELD_SUBJECT = "subject";
    public static final String FIELD_OPERATION = "operation";
    public static final String FIELD_ALLOW = "allow";
    public static final String FIELD_EXPRESSION = "expression";

    public final @NotNull String roleName;
    public final @NotNull String subject;//file or directory path (wildcards supported)
    public final @NotNull String operation; // READ, WRITE, DELETE, etc.
    public final boolean allow;// allow or deny operation
    public final @Nullable String expression;// optional expression for dynamic allow/deny calculation

    public FSAccessRule(@NotNull String roleName,
                        @NotNull String subject,
                        @NotNull String operation,
                        boolean allow,
                        @Nullable String expression) {
        this.roleName = roleName;
        this.subject = subject;
        this.operation = operation;
        this.allow = allow;
        this.expression = expression;
    }

    public static @Nullable FSAccessRule deserialize(JSONObject jsonObject) {
        if (jsonObject == null) return null;
        String roleName = jsonObject.optString(FIELD_ROLE_NAME, null);
        String subject = jsonObject.optString(FIELD_SUBJECT, null);
        String operation = jsonObject.optString(FIELD_OPERATION, null);
        if (roleName == null || subject == null || operation == null) return null;
        boolean allow = jsonObject.optBoolean(FIELD_ALLOW, false);
        String expression = (jsonObject.has(FIELD_EXPRESSION) && !jsonObject.isNull(FIELD_EXPRESSION)) ? jsonObject.optString(FIELD_EXPRESSION, null) : null;
        return new FSAccessRule(roleName, subject, operation, allow, expression);
    }
}
