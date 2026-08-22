package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;

/**
 * Reads one cell of one row, as a stream - this is how large text and blob values are fetched
 * without loading them into memory.
 */
public class ReadCellOperation extends DBOperation<Database.CellDataStreamInfo> {
    public static final String OPERATION = "READ_CELL";

    public static class Params {
        public final @Nullable String table;
        public final @Nullable String column;
        public final @NotNull DBFilters filters;

        public Params(@Nullable String table, @Nullable String column, @NotNull DBFilters filters) {
            this.table = table;
            this.column = column;
            this.filters = filters;
        }

        public static Params parse(@Nullable String table, @Nullable String column,
                                   @Nullable String filtersJsonStr) throws DBOperationException {
            return new Params(table, column, DBFilters.parse(filtersJsonStr));
        }
    }

    private final @NotNull Params params;

    public ReadCellOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                             @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /**
     * @return the cell, whose {@code inputStream} is null when the value is null. The caller owns
     *         the stream and must close it - <b>after</b> it has been read, not before.
     * @throws DBOperationException {@code NOT_FOUND} when no row matches
     */
    @Override
    public Database.CellDataStreamInfo execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        String table = requireTable(params.table);
        if (params.column == null) {
            throw DBOperationException.badRequest("column parameter is required");
        }

        checkAllowed(db, user, table, OPERATION, Map.of(
                "column", params.column,
                "filters", params.filters.toString()
        ), User.DBRights.READ);

        Database.CellDataStreamInfo info = db.getSingleCellDataStream(table, params.column,
                Arrays.asList(params.filters.clauses()), Arrays.asList(params.filters.args()));
        if (info == null) {
            throw DBOperationException.notFound("No row matches the given filters");
        }
        return info;
    }
}
