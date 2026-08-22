package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DatabaseOperations;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Inserts one or more rows into a table.
 */
public class InsertOperation extends DBOperation<JSONObject> {
    public static final String OPERATION = "INSERT";

    /**
     * @param table the table to insert into
     * @param rows  the rows; a single-row request carries exactly one
     * @param multipleRows whether the request asked for a batch. It changes only the shape of the
     *                     answer - a batch of one still reports {@code generated_ids}
     */
    public static class Params {
        public final @Nullable String table;
        public final @NotNull List<JSONObject> rows;
        public final boolean multipleRows;

        public Params(@Nullable String table, @NotNull List<JSONObject> rows, boolean multipleRows) {
            this.table = table;
            this.rows = rows;
            this.multipleRows = multipleRows;
        }

        /**
         * Decodes the {@code values} parameter, which is either one row object or an array of them.
         */
        public static Params parse(@Nullable String table, @Nullable String valuesJsonStr) throws DBOperationException {
            //same order of complaints as the checks in execute(), so that a request missing both
            //is told about the table first
            requireTable(table);
            if (valuesJsonStr == null) {
                throw DBOperationException.badRequest("values parameter is required");
            }
            Object valuesJson;
            try {
                valuesJson = new JSONTokener(valuesJsonStr).nextValue();
            } catch (Exception e) {
                throw DBOperationException.badRequest("Invalid JSON data: " + e.getMessage());
            }
            if (valuesJson instanceof JSONObject) {
                return new Params(table, Collections.singletonList((JSONObject) valuesJson), false);
            }
            if (valuesJson instanceof JSONArray) {
                JSONArray rowsJson = (JSONArray) valuesJson;
                List<JSONObject> rows = new ArrayList<>(rowsJson.length());
                for (int i = 0; i < rowsJson.length(); i++) {
                    Object row = rowsJson.opt(i);
                    if (!(row instanceof JSONObject)) {
                        throw DBOperationException.badRequest("values array element #" + i + " is not a JSON object");
                    }
                    rows.add((JSONObject) row);
                }
                if (rows.isEmpty()) {
                    throw DBOperationException.badRequest("values array must not be empty");
                }
                return new Params(table, rows, true);
            }
            throw DBOperationException.badRequest("values must be a JSON object or an array of JSON objects");
        }
    }

    private final @NotNull Params params;

    public InsertOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                           @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    @Override
    public JSONObject execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        checkTableDataEditingEnabled();
        String table = requireTable(params.table);

        //rights are checked for every row before anything is inserted, so a forbidden row
        //can not be smuggled in together with allowed ones
        for (JSONObject row : params.rows) {
            checkAllowed(db, user, table, OPERATION, Map.of("values", row.toString()),
                    User.DBRights.CREATE);
        }

        JSONArray generatedIds = new JSONArray();
        for (JSONObject row : params.rows) {
            //a failure here leaves the scope and rolls back the rows already inserted
            generatedIds.put(db.insert(table, row));
        }

        JSONObject result = new JSONObject();
        if (params.multipleRows) {
            result.put("generated_ids", generatedIds);
        } else {
            result.put("generated_id", generatedIds.getLong(0));
        }
        return result;
    }
}
