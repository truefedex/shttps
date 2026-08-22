package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Reads a page of rows from a table.
 */
public class ReadTableOperation extends DBOperation<TableDataResult> {
    public static final String OPERATION = "READ_TABLE";

    public static class Params {
        public final @Nullable String table;
        public final @Nullable String columns;
        public final @Nullable Long offset;
        public final @Nullable Long limit;
        public final @Nullable String sort;
        /** Raw "sort-order" value; anything but "desc" (case-insensitively) sorts ascending. */
        public final @Nullable String sortDir;
        public final boolean includeRowId;
        public final boolean includeTotal;
        public final @NotNull DBFilters filters;
        /** Only affects how the caller renders rows; kept here so the rights evaluator sees it. */
        public final boolean rowsAsObjects;

        public Params(@Nullable String table, @Nullable String columns, @Nullable Long offset,
                      @Nullable Long limit, @Nullable String sort, @Nullable String sortDir,
                      boolean includeRowId, boolean includeTotal, boolean rowsAsObjects,
                      @NotNull DBFilters filters) {
            this.table = table;
            this.columns = columns;
            this.offset = offset;
            this.limit = limit;
            this.sort = sort;
            this.sortDir = sortDir;
            this.includeRowId = includeRowId;
            this.includeTotal = includeTotal;
            this.rowsAsObjects = rowsAsObjects;
            this.filters = filters;
        }
    }

    private final @NotNull Params params;

    public ReadTableOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                              @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /**
     * @return an open cursor over the page, and the unpaged row count if it was asked for. The
     *         caller owns and must close the cursor.
     */
    @Override
    public TableDataResult execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        String table = requireTable(params.table);

        checkAllowed(db, user, table, OPERATION, Map.of(
                "columns", params.columns != null ? params.columns : "",
                "offset", params.offset != null ? params.offset : "",
                "limit", params.limit != null ? params.limit : "",
                "sort", params.sort != null ? params.sort : "",
                "sortDir", params.sortDir != null ? params.sortDir : "",
                "includeRowId", params.includeRowId,
                "rowsAsObjects", params.rowsAsObjects,
                "includeTotal", params.includeTotal,
                "filters", params.filters.toString()
        ), User.DBRights.READ);

        Holder<Long> outTotal = params.includeTotal ? new Holder<>(0L) : null;
        //the read runs against the same DatabaseOperations the check just used, so both see one
        //and the same state of the database
        TableData data = db.getTableDataSecure(table,
                params.columns != null ? params.columns.split(",") : null,
                params.offset, params.limit,
                params.filters.clauses(), params.filters.args(),
                params.sort, params.sortDir != null && params.sortDir.equalsIgnoreCase("desc"),
                params.includeRowId, outTotal);
        return new TableDataResult(data, outTotal != null ? outTotal.get() : null);
    }
}
