package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.DatabaseOperations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Deletes the rows of a table that match the filters.
 */
public class DeleteOperation extends DBOperation<Integer> {
    public static final String OPERATION = "DELETE";

    public static class Params {
        public final @Nullable String table;
        public final @NotNull DBFilters filters;

        public Params(@Nullable String table, @NotNull DBFilters filters) {
            this.table = table;
            this.filters = filters;
        }

        public static Params parse(@Nullable String table, @Nullable String filtersJsonStr) throws DBOperationException {
            return new Params(table, DBFilters.parse(filtersJsonStr));
        }
    }

    private final @NotNull Params params;

    public DeleteOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                           @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /** @return the number of rows removed */
    @Override
    public Integer execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        checkTableDataEditingEnabled();
        String table = requireTable(params.table);

        checkAllowed(db, user, table, OPERATION, Map.of(
                "filters", params.filters.toString()
        ), User.DBRights.DELETE);

        return db.delete(table, params.filters.clauses(), params.filters.args());
    }
}
