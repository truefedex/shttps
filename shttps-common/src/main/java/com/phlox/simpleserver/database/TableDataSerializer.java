package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONArray;
import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns a {@link TableData} cursor into the JSON envelope the data endpoints answer with:
 * <pre>
 * {"total":42,"offset":0,"limit":100,"columns":["id","name"],"data":[[1,"a"],[2,"b"]]}
 * </pre>
 * where {@code total}, {@code offset}/{@code limit} and {@code columns} appear only when the
 * corresponding {@link Options} field is set.
 * <p>
 * It offers the same result two ways, and which one fits depends on the transport:
 * <ul>
 *     <li>{@link #streamTo} writes rows out as they are read, so a result far larger than memory
 *     can be answered - this is how the HTTP endpoints reply, on a thread that keeps reading after
 *     the handler has returned.</li>
 *     <li>{@link #materialize} reads everything first and hands back a finished
 *     {@link JSONObject}. A protocol that answers one command at a time needs this: it can not
 *     leave a cursor open and still move on, least of all inside a transaction that the next
 *     command is going to use.</li>
 * </ul>
 * Both take ownership of the cursor and close it on every path, including failures - an unclosed
 * {@link TableData} keeps a database connection open.
 */
public final class TableDataSerializer {
    private TableDataSerializer() {
    }

    /** Which of the optional envelope fields to emit, and how to shape the rows. */
    public static class Options {
        /** Rows as {@code {"col":value}} objects instead of positional arrays. */
        public boolean rowsAsObjects = false;
        /** Emitted as {@code offset}/{@code limit}, and applied: rows before {@code offset} are
         *  skipped and at most {@code limit} are written. Null means "no paging, emit everything". */
        public @Nullable Integer offset = null;
        public @Nullable Integer limit = null;
        /** Emitted as {@code total} when set - the full row count behind a paged result. */
        public @Nullable Long total = null;
        /** Emit the column names as {@code columns}. */
        public boolean includeColumnNames = false;

        public Options rowsAsObjects(boolean value) {
            this.rowsAsObjects = value;
            return this;
        }

        public Options paging(@Nullable Integer offset, @Nullable Integer limit) {
            this.offset = offset;
            this.limit = limit;
            return this;
        }

        public Options total(@Nullable Long total) {
            this.total = total;
            return this;
        }

        public Options includeColumnNames(boolean value) {
            this.includeColumnNames = value;
            return this;
        }
    }

    /**
     * Writes the envelope to {@code output} row by row, closing {@code data} when done.
     */
    public static void streamTo(@NotNull OutputStream output, @NotNull TableData data,
                                @NotNull Options options) throws Exception {
        try {
            write(output, prefix(data, options));
            boolean anyRows = true;
            if (options.offset != null && options.offset > 0) {
                anyRows = data.skip(options.offset);
            }
            if (anyRows) {
                int count = 0;
                while (data.next() && (options.limit == null || count < options.limit)) {
                    if (count > 0) {
                        write(output, ",");
                    }
                    write(output, row(data, options).toString());
                    count++;
                }
            }
            write(output, "]}");
        } finally {
            data.close();
        }
    }

    /**
     * Reads the whole result into one {@link JSONObject}, closing {@code data} when done.
     */
    public static @NotNull JSONObject materialize(@NotNull TableData data,
                                                  @NotNull Options options) throws Exception {
        try {
            JSONObject result = new JSONObject();
            if (options.total != null) {
                result.put("total", options.total.longValue());
            }
            if (options.offset != null) {
                result.put("offset", options.offset.intValue());
            }
            if (options.limit != null) {
                result.put("limit", options.limit.intValue());
            }
            if (options.includeColumnNames) {
                result.put("columns", new JSONArray(data.getColumnNames()));
            }
            JSONArray rows = new JSONArray();
            boolean anyRows = true;
            if (options.offset != null && options.offset > 0) {
                anyRows = data.skip(options.offset);
            }
            if (anyRows) {
                int count = 0;
                while (data.next() && (options.limit == null || count < options.limit)) {
                    rows.put(row(data, options));
                    count++;
                }
            }
            result.put("data", rows);
            return result;
        } finally {
            data.close();
        }
    }

    /**
     * Everything up to and including the opening bracket of {@code "data"}. Built as text rather
     * than through JSONObject so that the rows can follow one at a time.
     */
    private static String prefix(TableData data, Options options) {
        StringBuilder sb = new StringBuilder("{");
        if (options.total != null) {
            sb.append("\"total\":").append(options.total).append(",");
        }
        if (options.offset != null) {
            sb.append("\"offset\":").append(options.offset).append(",");
        }
        if (options.limit != null) {
            sb.append("\"limit\":").append(options.limit).append(",");
        }
        if (options.includeColumnNames) {
            sb.append("\"columns\":").append(new JSONArray(data.getColumnNames())).append(",");
        }
        return sb.append("\"data\":[").toString();
    }

    private static Object row(TableData data, Options options) {
        return options.rowsAsObjects ? data.currentRowToJsonObject() : data.currentRowToJson();
    }

    private static void write(OutputStream output, String text) throws Exception {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
