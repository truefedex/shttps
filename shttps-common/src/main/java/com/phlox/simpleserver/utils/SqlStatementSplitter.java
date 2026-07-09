package com.phlox.simpleserver.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a script of SQLite SQL statements into individual statements suitable for
 * {@code PreparedStatement.prepareStatement(query)}.
 *
 * A naive split on ';' breaks on constructs like {@code CREATE TRIGGER ... BEGIN ...; ...; END;}
 * where semicolons appear inside the trigger body. To decide whether a semicolon actually
 * terminates a statement, this class uses the same token-level state machine as SQLite's own
 * {@code sqlite3_complete()} (see complete.c in the SQLite sources): after seeing
 * {@code CREATE [TEMP|TEMPORARY] TRIGGER}, semicolons no longer terminate the statement until
 * an {@code END} token that directly follows a semicolon is seen.
 *
 * The tokenizer also skips statement separators inside single-quoted strings, double-quoted /
 * backtick / [bracket] identifiers, line comments (--) and block comments.
 *
 * Statement text is preserved verbatim (including comments and internal semicolons of trigger
 * bodies); only the terminating semicolon between statements is dropped, and segments consisting
 * solely of whitespace/comments are not emitted.
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    // Token types, matching sqlite3_complete()
    private static final int TK_SEMI = 0;
    private static final int TK_WS = 1;
    private static final int TK_OTHER = 2;
    private static final int TK_EXPLAIN = 3;
    private static final int TK_CREATE = 4;
    private static final int TK_TEMP = 5;
    private static final int TK_TRIGGER = 6;
    private static final int TK_END = 7;

    // Parser states, matching sqlite3_complete()
    private static final int ST_INVALID = 0; // nothing seen yet in this statement
    private static final int ST_START = 1;   // statement just completed
    private static final int ST_NORMAL = 2;  // inside an ordinary statement
    private static final int ST_EXPLAIN = 3; // statement started with EXPLAIN
    private static final int ST_CREATE = 4;  // CREATE [TEMP] seen, watching for TRIGGER
    private static final int ST_TRIGGER = 5; // inside a CREATE TRIGGER statement
    private static final int ST_SEMI = 6;    // semicolon seen inside a trigger body
    private static final int ST_END = 7;     // END seen right after a semicolon in a trigger

    // TRANS[state][token] -> next state; a semicolon terminates a statement iff it moves
    // the machine into ST_START.
    private static final byte[][] TRANS = {
            /*                 SEMI WS OTHER EXPLAIN CREATE TEMP TRIGGER END */
            /* ST_INVALID */ {   1,  0,   2,     3,     4,   2,     2,   2},
            /* ST_START   */ {   1,  1,   2,     3,     4,   2,     2,   2},
            /* ST_NORMAL  */ {   1,  2,   2,     2,     2,   2,     2,   2},
            /* ST_EXPLAIN */ {   1,  3,   3,     2,     4,   2,     2,   2},
            /* ST_CREATE  */ {   1,  4,   2,     2,     2,   4,     5,   2},
            /* ST_TRIGGER */ {   6,  5,   5,     5,     5,   5,     5,   5},
            /* ST_SEMI    */ {   6,  6,   5,     5,     5,   5,     5,   7},
            /* ST_END     */ {   1,  7,   5,     5,     5,   5,     5,   5},
    };

    public static List<String> split(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasContent = false; // current segment contains something besides whitespace/comments
        int state = ST_START;

        int len = sql.length();
        int i = 0;
        while (i < len) {
            int tokenStart = i;
            char c = sql.charAt(i);
            int token;

            if (c == ';') {
                i++;
                token = TK_SEMI;
            } else if (Character.isWhitespace(c)) {
                do {
                    i++;
                } while (i < len && Character.isWhitespace(sql.charAt(i)));
                token = TK_WS;
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < len && sql.charAt(i) != '\n') {
                    i++;
                }
                token = TK_WS;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, len);
                token = TK_WS;
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                token = TK_OTHER;
            } else if (c == '[') {
                i++;
                while (i < len && sql.charAt(i) != ']') {
                    i++;
                }
                if (i < len) {
                    i++;
                }
                token = TK_OTHER;
            } else if (isIdentifierChar(c)) {
                do {
                    i++;
                } while (i < len && isIdentifierChar(sql.charAt(i)));
                token = classifyKeyword(sql, tokenStart, i);
            } else {
                i++;
                token = TK_OTHER;
            }

            state = TRANS[state][token];

            if (token == TK_SEMI && state == ST_START) {
                // this semicolon terminates a statement
                String stmt = current.toString().trim();
                if (hasContent && !stmt.isEmpty()) {
                    result.add(stmt);
                }
                current.setLength(0);
                hasContent = false;
            } else {
                current.append(sql, tokenStart, i);
                if (token != TK_WS) {
                    hasContent = true;
                }
            }
        }

        String tail = current.toString().trim();
        if (hasContent && !tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    /**
     * Skips a quoted literal/identifier starting at {@code start} (which holds the quote char).
     * A doubled quote is an escaped quote. Returns the index just past the closing quote, or
     * {@code sql.length()} if the literal is unterminated.
     */
    private static int skipQuoted(String sql, int start, char quote) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2; // escaped quote
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static boolean isIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '$' || c >= 0x80;
    }

    private static int classifyKeyword(String sql, int start, int end) {
        int n = end - start;
        if (n == 3 && sql.regionMatches(true, start, "END", 0, 3)) return TK_END;
        if (n == 4 && sql.regionMatches(true, start, "TEMP", 0, 4)) return TK_TEMP;
        if (n == 6 && sql.regionMatches(true, start, "CREATE", 0, 6)) return TK_CREATE;
        if (n == 7 && sql.regionMatches(true, start, "EXPLAIN", 0, 7)) return TK_EXPLAIN;
        if (n == 7 && sql.regionMatches(true, start, "TRIGGER", 0, 7)) return TK_TRIGGER;
        if (n == 9 && sql.regionMatches(true, start, "TEMPORARY", 0, 9)) return TK_TEMP;
        return TK_OTHER;
    }
}
