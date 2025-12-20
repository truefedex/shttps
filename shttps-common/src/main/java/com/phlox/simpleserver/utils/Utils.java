package com.phlox.simpleserver.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class Utils {
    public static String formatFileSize(long size) {
        if (size <= 0) {
            return "0 B";
        }

        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format(Locale.US,
                "%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static String getFileExtensionFromFilename(String filePath){
        int pos = filePath.lastIndexOf(".");
        if(pos > 0)
            return filePath.substring(pos + 1).toLowerCase();
        return null;
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] array = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (int i = 0; i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if(hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String hashFNV1a32(String dataStr) {
        byte[] data = dataStr.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (int i = 0; i < data.length; i++) {
            hash ^= (data[i] & 0xff);
            hash *= 16777619;
        }
        return Integer.toHexString(hash);
    }

    public static boolean contains(String[] array, String value) {
        for (String s : array) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static String makeFolderNameCompatible(String originalName) {
        String regex = "[^a-zA-Z0-9 .-]+";
        String cleanedName = originalName.replaceAll(regex, "_");
        cleanedName = cleanedName.trim().replaceAll("\\s+", " ");
        return cleanedName;
    }

    public static Pattern wildcardToRegex(String wildcard) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < wildcard.length(); i++) {
            char c = wildcard.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append(".");
                    break;
                case '.': case '\\': case '+': case '(':
                case ')': case '^': case '$': case '{':
                case '}': case '[': case ']': case '|':
                case '/':
                    sb.append("\\").append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append("$");
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public static List<String> splitSqlStatementsSQLite(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;   // '...'
        boolean inDouble = false;   // "..."
        boolean inLineComment = false;  // --
        boolean inBlockComment = false; // /* ... */

        int len = sql.length();

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : '\0';

            // --- Handle comments ---

            // Line comment --
            if (!inSingle && !inDouble && !inBlockComment &&
                    c == '-' && next == '-') {
                inLineComment = true;
                current.append(c);
                continue;
            }

            // End of line comment
            if (inLineComment && c == '\n') {
                inLineComment = false;
            }

            // Block comment /* ... */
            if (!inSingle && !inDouble && !inLineComment &&
                    c == '/' && next == '*') {
                inBlockComment = true;
                current.append(c);
                continue;
            }

            // End block comment */
            if (inBlockComment && c == '*' && next == '/') {
                inBlockComment = false;
                current.append(c);
                current.append(next);
                i++;
                continue;
            }

            if (inLineComment || inBlockComment) {
                current.append(c);
                continue;
            }

            // --- Handle string literals ---

            // single-quoted strings: '...'
            if (!inDouble && c == '\'') {
                // check for escaped ''
                if (inSingle && next == '\'') {
                    current.append(c);
                    current.append(next);
                    i++;
                    continue;
                }

                inSingle = !inSingle;
                current.append(c);
                continue;
            }

            // double-quoted strings: "..."
            if (!inSingle && c == '"') {
                // check for escaped ""
                if (inDouble && next == '"') {
                    current.append(c);
                    current.append(next);
                    i++;
                    continue;
                }

                inDouble = !inDouble;
                current.append(c);
                continue;
            }

            // --- Statement separator ---
            if (!inSingle && !inDouble && c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    result.add(stmt);
                }
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }

        return result;
    }

    public static Map<String, Object> toMap(JSONObject jsonObject) {
        Map<String, Object> results = new HashMap<String, Object>();
        for (String key : jsonObject.keySet()) {
            Object jsonValue = jsonObject.get(key);
            Object value;
            if (jsonValue == null || JSONObject.NULL.equals(jsonValue)) {
                value = null;
            } else if (jsonValue instanceof JSONObject) {
                value = toMap((JSONObject) jsonValue);
            } else if (jsonValue instanceof JSONArray) {
                value = toList((JSONArray) jsonValue);
            } else {
                value = jsonValue;
            }
            results.put(key, value);
        }
        return results;
    }

    public static List<Object> toList(JSONArray jsonArray) {
        List<Object> results = new ArrayList<Object>(jsonArray.length());
        for (int i = 0; i < jsonArray.length(); i++) {
            Object element = jsonArray.get(i);
            if (element == null || JSONObject.NULL.equals(element)) {
                results.add(null);
            } else if (element instanceof JSONArray) {
                results.add(toList((JSONArray) element));
            } else if (element instanceof JSONObject) {
                results.add(toMap((JSONObject) element));
            } else {
                results.add(element);
            }
        }
        return results;
    }
}
