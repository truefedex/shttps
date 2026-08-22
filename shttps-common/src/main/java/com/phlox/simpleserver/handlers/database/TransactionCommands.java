package com.phlox.simpleserver.handlers.database;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.TableDataSerializer;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.operations.CustomSqlOperation;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.database.operations.DeleteOperation;
import com.phlox.simpleserver.database.operations.InsertOperation;
import com.phlox.simpleserver.database.operations.ReadTableOperation;
import com.phlox.simpleserver.database.operations.TableDataResult;
import com.phlox.simpleserver.database.operations.UpdateOperation;

import org.json.JSONArray;
import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The commands {@link DBTransactionWebSocketHandler} accepts, each a thin adapter over one of the
 * operations in {@code database.operations}.
 * <p>
 * An adapter does two things and no more: it turns a command frame into that operation's
 * {@code Params}, and it turns the operation's result into the JSON that goes in the reply's
 * {@code result} field. Everything else - whether the feature is switched on, whether this user may
 * do it, what the operation actually does - already lives in the operation and is not repeated here.
 * That is what keeps a command from quietly disagreeing with its HTTP equivalent.
 * <p>
 * Unlike the HTTP endpoints, whose parameters are all strings because form encoding has nothing
 * else, a command frame carries real JSON. So the adapters build {@code Params} through their
 * constructors and {@link DBFilters#of(JSONObject)} rather than through the {@code parse(String…)}
 * factories, and {@code filters} and {@code values} are nested objects rather than strings holding
 * JSON.
 */
final class TransactionCommands {
    static final String COMMAND_QUERY = "query";
    static final String COMMAND_INSERT = "insert";
    static final String COMMAND_UPDATE = "update";
    static final String COMMAND_DELETE = "delete";
    static final String COMMAND_SELECT = "select";
    static final String COMMAND_COMMIT = "commit";

    private TransactionCommands() {
    }

    /** One command, ready to run inside the transaction. */
    interface TransactionCommand {
        /**
         * @return the payload for the reply's {@code result} field, or null when the command
         *         produced none
         */
        JSONObject execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception;
    }

    /**
     * Builds the command a frame asks for.
     *
     * @param defaultLimit rows returned when a reading command does not say
     * @param maxLimit     rows returned however many it asks for - a result is read into memory
     *                     while the write thread is held, so the client does not get to choose
     * @throws DBOperationException of kind {@code BAD_REQUEST} for anything unrecognised or
     *         malformed. The caller turns that into an ordinary error reply, in its turn.
     */
    static TransactionCommand parse(@NotNull JSONObject frame, @NotNull SHTTPSConfig config,
                                    @NotNull AuthManager authManager,
                                    int defaultLimit, int maxLimit) throws DBOperationException {
        String command = frame.optString("command", "");
        switch (command) {
            case COMMAND_QUERY:
                return parseQuery(frame, config, authManager, defaultLimit, maxLimit);
            case COMMAND_INSERT:
                return parseInsert(frame, config, authManager);
            case COMMAND_UPDATE:
                return parseUpdate(frame, config, authManager);
            case COMMAND_DELETE:
                return parseDelete(frame, config, authManager);
            case COMMAND_SELECT:
                return parseSelect(frame, config, authManager, defaultLimit, maxLimit);
            default:
                throw DBOperationException.badRequest("Unknown command: " + command);
        }
    }

    // ---- parsing ----

    private static TransactionCommand parseQuery(JSONObject frame, SHTTPSConfig config,
                                                 AuthManager authManager, int defaultLimit,
                                                 int maxLimit) throws DBOperationException {
        if (!frame.has("sql")) {
            throw DBOperationException.badRequest("sql is required");
        }
        CustomSqlOperation.Params params = new CustomSqlOperation.Params(frame.optString("sql"));
        int limit = clampLimit(frame, defaultLimit, maxLimit);
        int offset = Math.max(0, frame.optInt("offset", 0));
        boolean includeNames = frame.optBoolean("includeNames", false);
        return (db, user) -> {
            TableData data = new CustomSqlOperation(config, authManager, params).execute(db, user);
            //this endpoint pages in the answer rather than in SQL: the statement runs whole and the
            //serializer takes a slice, exactly as POST /api/db/query does
            return data == null ? null : TableDataSerializer.materialize(data,
                    new TableDataSerializer.Options()
                            .paging(offset, limit)
                            .includeColumnNames(includeNames));
        };
    }

    private static TransactionCommand parseInsert(JSONObject frame, SHTTPSConfig config,
                                                  AuthManager authManager) throws DBOperationException {
        String table = requireTable(frame);
        //InsertOperation.Params.parse takes a JSON string, so its rules are replicated here against
        //the real JSON: an object is one row and answers with generated_id, an array - even of one -
        //is a batch and answers with generated_ids
        Object values = frame.opt("values");
        if (values == null || JSONObject.NULL.equals(values)) {
            throw DBOperationException.badRequest("values is required");
        }
        InsertOperation.Params params;
        if (values instanceof JSONObject) {
            params = new InsertOperation.Params(table,
                    Collections.singletonList((JSONObject) values), false);
        } else if (values instanceof JSONArray) {
            JSONArray array = (JSONArray) values;
            List<JSONObject> rows = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                Object row = array.opt(i);
                if (!(row instanceof JSONObject)) {
                    throw DBOperationException.badRequest("values array element #" + i + " is not a JSON object");
                }
                rows.add((JSONObject) row);
            }
            if (rows.isEmpty()) {
                throw DBOperationException.badRequest("values array must not be empty");
            }
            params = new InsertOperation.Params(table, rows, true);
        } else {
            throw DBOperationException.badRequest("values must be a JSON object or an array of JSON objects");
        }
        //already the {"generated_id":n} / {"generated_ids":[..]} the HTTP endpoint answers with
        return (db, user) -> new InsertOperation(config, authManager, params).execute(db, user);
    }

    private static TransactionCommand parseUpdate(JSONObject frame, SHTTPSConfig config,
                                                  AuthManager authManager) throws DBOperationException {
        String table = requireTable(frame);
        Object values = frame.opt("values");
        if (!(values instanceof JSONObject)) {
            throw DBOperationException.badRequest("values must be a JSON object");
        }
        UpdateOperation.Params params = new UpdateOperation.Params(table, (JSONObject) values,
                filters(frame));
        return (db, user) -> new JSONObject().put("updated_rows",
                new UpdateOperation(config, authManager, params).execute(db, user).intValue());
    }

    private static TransactionCommand parseDelete(JSONObject frame, SHTTPSConfig config,
                                                  AuthManager authManager) throws DBOperationException {
        DeleteOperation.Params params = new DeleteOperation.Params(requireTable(frame), filters(frame));
        return (db, user) -> new JSONObject().put("deleted_rows",
                new DeleteOperation(config, authManager, params).execute(db, user).intValue());
    }

    private static TransactionCommand parseSelect(JSONObject frame, SHTTPSConfig config,
                                                  AuthManager authManager, int defaultLimit,
                                                  int maxLimit) throws DBOperationException {
        //named one by one on purpose: the Params constructor takes rowsAsObjects before filters
        //while the class declares them the other way round, and swapping two booleans compiles
        String table = requireTable(frame);
        String columns = frame.isNull("columns") ? null : frame.optString("columns", null);
        Long offset = (long) Math.max(0, frame.optInt("offset", 0));
        Long limit = (long) clampLimit(frame, defaultLimit, maxLimit);
        String sort = frame.isNull("sort") ? null : frame.optString("sort", null);
        String sortDir = frame.isNull("sortOrder") ? null : frame.optString("sortOrder", null);
        boolean includeRowId = frame.optBoolean("includeRowId", false);
        boolean includeTotal = frame.optBoolean("includeTotal", false);
        boolean rowsAsObjects = frame.optBoolean("rowsAsObjects", false);
        DBFilters filters = filters(frame);

        ReadTableOperation.Params params = new ReadTableOperation.Params(table, columns, offset,
                limit, sort, sortDir, includeRowId, includeTotal, rowsAsObjects, filters);
        return (db, user) -> {
            TableDataResult result = new ReadTableOperation(config, authManager, params).execute(db, user);
            //the page window was applied in SQL, so the serializer must not apply it again - only
            //the shape of the rows and the total are left to it, as in GET /api/db/table
            return TableDataSerializer.materialize(result.data,
                    new TableDataSerializer.Options()
                            .rowsAsObjects(rowsAsObjects)
                            .total(result.total));
        };
    }

    // ---- shared bits ----

    private static String requireTable(JSONObject frame) throws DBOperationException {
        String table = frame.isNull("table") ? null : frame.optString("table", null);
        if (table == null || table.isEmpty()) {
            throw DBOperationException.badRequest("table is required");
        }
        return table;
    }

    /** Filters arrive as a nested object here, not as a string holding one. */
    private static DBFilters filters(JSONObject frame) throws DBOperationException {
        if (!frame.has("filters") || frame.isNull("filters")) {
            return DBFilters.EMPTY;
        }
        Object filters = frame.opt("filters");
        if (!(filters instanceof JSONObject)) {
            throw DBOperationException.badRequest("filters must be a JSON object");
        }
        return DBFilters.of((JSONObject) filters);
    }

    private static int clampLimit(JSONObject frame, int defaultLimit, int maxLimit) {
        return Math.max(0, Math.min(maxLimit, frame.optInt("limit", defaultLimit)));
    }
}
