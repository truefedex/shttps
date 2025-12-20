package com.phlox.simpleserver.auth;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
    private final @NonNull Holder<Database> dbHolder;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public UserRightsEvaluator(@NonNull Holder<Database> dbHolder) {
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

    private @Nullable DBAccessRule findMatchingDBAccessRule(@NonNull DatabaseOperations db, @NonNull User user, String subject, String operation) {
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
        } catch (Exception e) {
            logger.stackTrace(e);
            return null;
        }
    }

    private @Nullable FSAccessRule findMatchingFSAccessRule(@NonNull DatabaseOperations db, @NonNull User user, String subject, String operation) {
        //Try to find specific rule for this user role and subject+operation
        //As incoming subject is file or directory path and rule subject is always directory so we can use LIKE operator for matching
        //If found several matching rules, the most specific (longest) rule will be used
        try (TableData data = db.query("SELECT * FROM " + FSAccessRule.FS_ACCESS_RULES_TABLE_NAME +
                        " WHERE role_name=? AND ? LIKE subject || '%' AND operation=? " +
                        " ORDER BY LENGTH(subject) DESC LIMIT 1",
                new Object[]{user.role, subject, operation}, false)) {
            if (data.next()) {
                return FSAccessRule.deserialize(data.currentRowToJsonObject());
            } else {
                return null;
            }
        } catch (Exception e) {
            logger.stackTrace(e);
            return null;
        }
    }

    public boolean hasAnyFileEditingRights(@NonNull User user) {
        EnumSet<User.FileSystemRights> fsRights = userFSRights(user);
        return fsRights.contains(User.FileSystemRights.CREATE) ||
                fsRights.contains(User.FileSystemRights.UPDATE) ||
                fsRights.contains(User.FileSystemRights.DELETE);
    }

    public EnumSet<User.FileSystemRights> userFSRights(@NonNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.fsRights : user.fsRights;
    }

    public EnumSet<User.DBRights> userDBRights(@NonNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.dbRights : user.dbRights;
    }

    public EnumSet<User.SystemRights> userSystemRights(@NonNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.systemRights : user.systemRights;
    }

    public Long getStorageLimit(User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.storageLimit : user.storageLimit;
    }

    private static boolean evaluateRuleExpression(@NonNull String expression, @NonNull DatabaseOperations db, @NonNull User user, @NonNull String subject,
                                                  @NonNull String operation, @Nullable Map<String, Object> operationParams) {
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

    public boolean checkIsDBOperationAllowed(@NonNull DatabaseOperations db, @NonNull User user, boolean checkRules, @NonNull String subject,
                                             @NonNull String operation, @Nullable Map<String, Object> operationParams,
                                             User.DBRights... requestedRights) {
        //first we trying to find specific rule for this user role because it has higher priority
        if (checkRules) {
            DBAccessRule rule = findMatchingDBAccessRule(db, user, subject, operation);
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

    public boolean checkIsFileOperationAllowed(@NonNull User user, boolean checkRules, @NonNull String subject,
                         @NonNull String operation, @Nullable Map<String, Object> operationParams,
                         User.FileSystemRights... requestedRights) throws Exception {
        //first we trying to find specific rule for this user role because it has higher priority
        Database db = dbHolder.get();
        if (db != null) {
            return db.runTransaction(db1 -> {
                if (checkRules) {
                    FSAccessRule rule = findMatchingFSAccessRule(db1, user, subject, operation);
                    if (rule != null) {
                        if (rule.expression != null && !rule.expression.isEmpty()) {
                            //evaluate expression
                            return evaluateRuleExpression(rule.expression, db1, user, subject, operation, operationParams);
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
