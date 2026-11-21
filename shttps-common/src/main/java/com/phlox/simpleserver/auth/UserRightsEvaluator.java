package com.phlox.simpleserver.auth;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UserRightsEvaluator {
    private static final Pattern PARAM_PATTERN =
            Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_.\\[\\]]*)");
    private static final Set<String> PROTECTED_TABLES = Set.of(
            "user",
            "user_role",
            "shttps_db_access_rule",
            "shttps_fs_access_rule"
    );
    private final @NotNull Holder<Database> dbHolder;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public UserRightsEvaluator(@NotNull Holder<Database> dbHolder) {
        this.dbHolder = dbHolder;
    }

    public @Nullable UserRole loadRole(String roleName) {
        return loadRole(dbHolder.get(), roleName);
    }
    private @Nullable UserRole loadRole(@Nullable DatabaseOperations db, String roleName) {
        if (roleName == null) return null;
        if (db == null) return null;
        try (TableData data = db.getTableDataSecure(UserRole.ROLES_TABLE_NAME, null, null, null,
                new String[]{"name="},
                new Object[]{roleName}, null, false, false, null)) {
            if (data.next()) {
                return UserRole.deserialize(data.currentRowToJsonObject());
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private @Nullable DBAccessRule findMatchingDBAccessRule(@NotNull DatabaseOperations db, @NotNull User user, String subject, String operation) throws Exception {
        //try to find specific rule for this user role and subject+operation
        //if not found for specific subject, try to find for wildcard subject '*'
        try (TableData data = db.query("SELECT * FROM " + DBAccessRule.DB_ACCESS_RULES_TABLE_NAME +
                        " WHERE role_name=? AND (subject=? OR subject='*') AND operation=? " +
                        " ORDER BY CASE WHEN subject='*' THEN 1 ELSE 0 END ASC LIMIT 1",
                new Object[]{user.role, subject, operation}, false)) {
            if (data.next()) {
                return DBAccessRule.deserialize(data.currentRowToJsonObject());
            } else {
                return null;
            }
        }
    }

    /**
     * Collapses a filesystem subject the same way {@code DocumentFileUtils.findChildByPath} walks
     * it: backslashes become slashes, and empty segments and {@code "."} disappear. {@code ".."} is
     * left in place, so a path the file lookup will reject cannot be rewritten into a rule that
     * covers a different file.
     */
    public static @NotNull String normalizeFsPath(@Nullable String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder normalized = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            normalized.append('/').append(part);
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    /**
     * Whether a stored rule subject covers {@code path}. Both sides are normalized first.
     * A subject matches itself and its descendants, and nothing that merely shares a string
     * prefix ({@code /secret} does not cover {@code /secrets}). {@code _} and {@code %} in the
     * subject are literal characters. Comparison is case-insensitive, which is what the previous
     * SQL {@code LIKE} did for ASCII and what a Windows volume does for the file itself.
     * The root subject {@code /} covers every path.
     */
    public static boolean fsSubjectCovers(@Nullable String subject, @Nullable String path) {
        String rule = normalizeFsPath(subject);
        String target = normalizeFsPath(path);
        if ("/".equals(rule)) return true;
        if (rule.equalsIgnoreCase(target)) return true;
        return target.length() > rule.length()
                && target.charAt(rule.length()) == '/'
                && target.regionMatches(true, 0, rule, 0, rule.length());
    }

    private @Nullable FSAccessRule findMatchingFSAccessRule(@NotNull DatabaseOperations db, @NotNull User user, String subject, String operation) throws Exception {
        //Every rule for this role and operation, then the longest subject that actually covers the
        //path. Doing the match here, rather than with "LIKE subject || '%'", keeps "_" and "%"
        //literal and requires a path-segment boundary after the subject.
        String normalized = normalizeFsPath(subject);
        try (TableData data = db.query("SELECT * FROM " + FSAccessRule.FS_ACCESS_RULES_TABLE_NAME +
                        " WHERE role_name=? AND operation=?",
                new Object[]{user.role, operation}, false)) {
            FSAccessRule best = null;
            int bestLength = -1;
            while (data.next()) {
                FSAccessRule rule = FSAccessRule.deserialize(data.currentRowToJsonObject());
                if (rule == null || !fsSubjectCovers(rule.subject, normalized)) continue;
                int length = normalizeFsPath(rule.subject).length();
                if (length > bestLength) {
                    best = rule;
                    bestLength = length;
                }
            }
            return best;
        }
    }

    public boolean hasAnyFileEditingRights(@NotNull User user) {
        EnumSet<User.FileSystemRights> fsRights = userFSRights(user);
        return fsRights.contains(User.FileSystemRights.CREATE) ||
                fsRights.contains(User.FileSystemRights.UPDATE) ||
                fsRights.contains(User.FileSystemRights.DELETE);
    }

    public EnumSet<User.FileSystemRights> userFSRights(@NotNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.fsRights : user.fsRights;
    }

    public EnumSet<User.DBRights> userDBRights(@NotNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.dbRights : user.dbRights;
    }

    public EnumSet<User.SystemRights> userSystemRights(@NotNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.systemRights : user.systemRights;
    }

    public EnumSet<User.ChannelRights> userChannelRights(@NotNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.channelRights : user.channelRights;
    }

    public Long getStorageLimit(User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.storageLimit : user.storageLimit;
    }

    private static boolean evaluateRuleExpression(@NotNull String expression, @NotNull DatabaseOperations db, @NotNull User user, @NotNull String subject,
                                                  @NotNull String operation, @Nullable Map<String, Object> operationParams) {
        Map<String, Object> availableVariables = new HashMap<>();
        availableVariables.put("user.id", user.identity);
        availableVariables.put("user.role", user.role);
        availableVariables.put("user.rootDir", user.rootDir);
        availableVariables.put("user.usedStorage", user.usedStorage);
        availableVariables.put("subject", subject);
        availableVariables.put("operation", operation);
        if (operationParams != null) {
            for (Map.Entry<String, Object> entry : operationParams.entrySet()) {
                availableVariables.put("param." + entry.getKey(), entry.getValue());
            }
        }
        Matcher m = PARAM_PATTERN.matcher(expression);
        StringBuffer sb = new StringBuffer();
        List<Object> args = new ArrayList<>();
        while (m.find()) {
            String variable = m.group(1);
            Object value = availableVariables.get(variable);
            args.add(value);
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        Object[] objArgs = args.toArray(new Object[0]);
        try (TableData result = db.query(sb.toString(), objArgs, true)) {
            if (result.next()) {
                //first column contains evaluation result
                return result.getBoolean(0);
            } else {
                return false;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean checkIsDBOperationAllowed(@NotNull DatabaseOperations db, @NotNull User user, boolean checkRules, @NotNull String subject,
                                             @NotNull String operation, @Nullable Map<String, Object> operationParams,
                                             User.DBRights... requestedRights) {
        //first we trying to find specific rule for this user role because it has higher priority
        if (checkRules) {
            DBAccessRule rule;
            try {
                rule = findMatchingDBAccessRule(db, user, subject, operation);
            } catch (Exception e) {
                //a failed lookup must not fall through to the role bitmask: that turns a deny
                //the query could not read into an allow
                logger.stackTrace(e);
                return false;
            }
            if (rule != null) {
                if (rule.expression != null && !rule.expression.isEmpty()) {
                    //evaluate expression
                    return evaluateRuleExpression(rule.expression, db, user, subject, operation, operationParams);
                } else {
                    return rule.allow;
                }
            }
        }
        //no specific rule found, check general DB rights
        UserRole role = loadRole(db, user.role);
        EnumSet<User.DBRights> dbRights = role != null ? role.dbRights : user.dbRights;
        if (PROTECTED_TABLES.contains(subject) &&
                !dbRights.contains(User.DBRights.EXEC_SQL)) {
            //Logic for protected tables:
            //* access to protected tables should be configured via specific rules only
            //* but users with EXEC_SQL right anyway have full access so we allowing them to proceed
            return false;
        }
        return dbRights.containsAll(List.of(requestedRights));
    }

    /**
     * Paths strictly inside {@code subject} that a rule for {@code operation} denies to this
     * user's role, normalized. Empty when rules are not in use.
     * <p>
     * Rules are set on folders and match their whole subtree, and every other check asks about the
     * one path an operation names. An operation that works on a whole tree - deleting, moving,
     * renaming or copying a folder, zipping it, searching in it - therefore never consulted a deny
     * rule placed on a folder further down, and a subfolder kept from a user was deleted, copied
     * somewhere readable or zipped through its parent. One query covers the whole subtree.
     * <p>
     * A rule with an expression is evaluated with its own subject. A lookup that fails throws, so
     * that the caller refuses rather than allows.
     */
    public @NotNull List<String> findDeniedDescendants(@NotNull User user, boolean checkRules,
                                                       @NotNull String subject, @NotNull String operation,
                                                       @Nullable Map<String, Object> operationParams) throws Exception {
        Database db = dbHolder.get();
        if (!checkRules || db == null || user.role == null) return new ArrayList<>();
        String normalized = normalizeFsPath(subject);
        return db.runTransaction(db1 -> {
            List<String> denied = new ArrayList<>();
            try (TableData data = db1.query("SELECT * FROM " + FSAccessRule.FS_ACCESS_RULES_TABLE_NAME +
                            " WHERE role_name=? AND operation=?",
                    new Object[]{user.role, operation}, false)) {
                while (data.next()) {
                    FSAccessRule rule = FSAccessRule.deserialize(data.currentRowToJsonObject());
                    if (rule == null) continue;
                    String ruleSubject = normalizeFsPath(rule.subject);
                    if (ruleSubject.equalsIgnoreCase(normalized) || !fsSubjectCovers(normalized, ruleSubject)) {
                        continue;//not strictly inside: the ordinary check already decided it
                    }
                    boolean allowed = rule.expression != null && !rule.expression.isEmpty()
                            ? evaluateRuleExpression(rule.expression, db1, user, ruleSubject, operation, operationParams)
                            : rule.allow;
                    if (!allowed) denied.add(ruleSubject);
                }
            }
            return denied;
        });
    }

    public boolean checkIsFileOperationAllowed(@NotNull User user, boolean checkRules, @NotNull String subject,
                         @NotNull String operation, @Nullable Map<String, Object> operationParams,
                         User.FileSystemRights... requestedRights) throws Exception {
        //first we trying to find specific rule for this user role because it has higher priority
        Database db = dbHolder.get();
        if (db != null) {
            String normalizedSubject = normalizeFsPath(subject);
            return db.runTransaction(db1 -> {
                if (checkRules) {
                    FSAccessRule rule;
                    try {
                        rule = findMatchingFSAccessRule(db1, user, normalizedSubject, operation);
                    } catch (Exception e) {
                        //a failed lookup must not fall through to the role bitmask: that turns a
                        //deny the query could not read into an allow
                        logger.stackTrace(e);
                        return false;
                    }
                    if (rule != null) {
                        if (rule.expression != null && !rule.expression.isEmpty()) {
                            //evaluate expression
                            return evaluateRuleExpression(rule.expression, db1, user, normalizedSubject, operation, operationParams);
                        } else {
                            return rule.allow;
                        }
                    }
                }
                //no specific rule found, check general FS rights
                UserRole role = loadRole(db1, user.role);
                EnumSet<User.FileSystemRights> fsRights = role != null ? role.fsRights : user.fsRights;
                return fsRights.containsAll(List.of(requestedRights));
            });
        } else {
            //no database, no roles (its only can be stored in database) check general FS rights
            return user.fsRights.containsAll(List.of(requestedRights));
        }
    }
}
