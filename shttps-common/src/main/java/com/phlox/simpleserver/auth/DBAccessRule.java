package com.phlox.simpleserver.auth;

import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class DBAccessRule {
    public static final String DB_ACCESS_RULES_TABLE_NAME = "shttps_db_access_rule";
    public static final String FIELD_ROLE_NAME = "role_name";
    public static final String FIELD_SUBJECT = "subject";
    public static final String FIELD_OPERATION = "operation";
    public static final String FIELD_ALLOW = "allow";
    public static final String FIELD_EXPRESSION = "expression";

    public final @NonNull String roleName;
    public final @NonNull String subject;//table or view name
    public final @NonNull String operation;// SELECT, INSERT, UPDATE, DELETE, etc.
    public final boolean allow;// allow or deny operation
    public final @Nullable String expression;// optional expression for dynamic allow/deny calculation

    public DBAccessRule(@NonNull String roleName,
                        @NonNull String subject,
                        @NonNull String operation,
                        boolean allow,
                        @Nullable String expression) {
        this.roleName = roleName;
        this.subject = subject;
        this.operation = operation;
        this.allow = allow;
        this.expression = expression;
    }

    public static @Nullable DBAccessRule deserialize(JSONObject jsonObject) {
        if (jsonObject == null) return null;
        String roleName = jsonObject.optString(FIELD_ROLE_NAME, null);
        String subject = jsonObject.optString(FIELD_SUBJECT, null);
        String operation = jsonObject.optString(FIELD_OPERATION, null);
        if (roleName == null || subject == null || operation == null) return null;
        boolean allow = jsonObject.optInt(FIELD_ALLOW, 0) > 0;
        String expression = (jsonObject.has(FIELD_EXPRESSION) && !jsonObject.isNull(FIELD_EXPRESSION)) ? jsonObject.optString(FIELD_EXPRESSION, null) : null;
        return new DBAccessRule(roleName, subject, operation, allow, expression);
    }
}
