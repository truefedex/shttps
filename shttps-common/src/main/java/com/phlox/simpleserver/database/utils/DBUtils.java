package com.phlox.simpleserver.database.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DBUtils {
    /**
    Check if the given string is a valid column name
     */
    public static boolean isValidColumnName(String name) {
        return name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    /**
    Check if the given string is a valid table name
     */
    public static boolean isValidTableName(String name) {
        return name.matches("[a-zA-Z_][a-zA-Z0-9_]*");
    }

    /**
    Check if the given where clause is valid and safe (no sql injection)
    @param whereFilters - array of filters. Each in format "column[=><][!?]" where:
     "=" - equal
     ">" - greater
     "<" - less
     "]" - greater or equal
     "[" - less or equal
     "!" - not equal
     "?" - like
     "∈" - in
     "∉" - not in
     */
    public static String buildSimpleWhereStatement(String[] whereFilters) throws SecurityException {
        StringBuilder where = new StringBuilder();
        Pattern pattern = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)([=><\\]\\[!?]|∈[1-9]\\d*|∉[1-9]\\d*)");
        for (int i = 0; i < whereFilters.length; i++) {
            if (i > 0) {
                where.append(" AND ");
            }
            String filter = whereFilters[i];
            Matcher matcher = pattern.matcher(filter);
            if (filter.isEmpty() || !matcher.matches()) {
                throw new SecurityException("Invalid where filter: " + filter);
            }

            String column = matcher.group(1);
            String operator = matcher.group(2);
            where.append(column);
            switch (operator) {
                case "=":
                case ">":
                case "<":
                    where.append(operator).append("?");
                    break;
                case "]":
                    where.append(">=?");
                    break;
                case "[":
                    where.append("<=?");
                    break;
                case "!":
                    where.append("!=?");
                    break;
                case "?":
                    where.append(" LIKE ?");
                    break;
                default:
                    if (operator.startsWith("∈")) {
                        where.append(" IN (");
                        int count = Integer.parseInt(operator.substring(1));
                        for (int j = 0; j < count; j++) {
                            if (j > 0) {
                                where.append(",");
                            }
                            where.append("?");
                        }
                        where.append(")");
                    } else if (operator.startsWith("∉")) {
                        where.append(" NOT IN (");
                        int count = Integer.parseInt(operator.substring(1));
                        for (int j = 0; j < count; j++) {
                            if (j > 0) {
                                where.append(",");
                            }
                            where.append("?");
                        }
                        where.append(")");
                    }
            }
        }
        return where.toString();
    }
}
