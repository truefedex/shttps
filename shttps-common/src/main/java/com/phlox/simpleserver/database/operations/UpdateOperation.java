package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.DatabaseOperations;

import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Updates the rows of a table that match the filters.
 */
public class UpdateOperation extends DBOperation<Integer> {
    public static final String OPERATION = "UPDATE";

    public static class Params {
        public final @Nullable String table;
        public final @NotNull JSONObject values;
        public final @NotNull DBFilters filters;

        public Params(@Nullable String table, @NotNull JSONObject values, @NotNull DBFilters filters) {
            this.table = table;
            this.values = values;
            this.filters = filters;
        }

        public static Params parse(@Nullable String table, @Nullable String valuesJsonStr,
                                   @Nullable String filtersJsonStr) throws DBOperationException {
            JSONObject values;
            try {
                values = new JSONObject(valuesJsonStr);
            } catch (Exception e) {
                throw DBOperationException.badRequest("Invalid JSON data: " + e.getMessage());
            }
            return new Params(table, values, DBFilters.parse(filtersJsonStr));
        }
    }

    private final @NotNull Params params;

    public UpdateOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                           @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /** @return the number of rows changed */
    @Override
    public Integer execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        checkTableDataEditingEnabled();
        String table = requireTable(params.table);

        checkAllowed(db, user, table, OPERATION, Map.of(
                "values", params.values.toString(),
                "filters", params.filters.toString()
        ), User.DBRights.UPDATE);

        return db.update(table, params.values, params.filters.clauses(), params.filters.args());
    }
}
