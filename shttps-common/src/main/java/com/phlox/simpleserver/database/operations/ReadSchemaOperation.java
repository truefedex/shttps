package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.model.Table;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the database schema: either every table, or one named table.
 */
public class ReadSchemaOperation extends DBOperation<Table[]> {
    public static final String OPERATION = "READ_SCHEMA";
    /** Subject used when the request is about the database as a whole. */
    public static final String ALL_TABLES_SUBJECT = "*";

    public static class Params {
        /** The one table asked about, or null for all of them. */
        public final @Nullable String table;

        public Params(@Nullable String table) {
            this.table = table;
        }
    }

    private final @NotNull Params params;

    public ReadSchemaOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                               @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /**
     * @return every table, or an array holding just the requested one
     * @throws DBOperationException {@code NOT_FOUND} when the named table does not exist
     */
    @Override
    public Table[] execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        checkAllowed(db, user, params.table != null ? params.table : ALL_TABLES_SUBJECT,
                OPERATION, null, User.DBRights.READ_SCHEMA);

        //read through the transaction's operations, so the schema is the one the check was made
        //against rather than whatever it happens to be a moment later
        Table[] tables = db.getTables();
        if (tables == null) {
            throw DBOperationException.notFound("No schema information available");
        }
        if (params.table == null) {
            return tables;
        }
        for (Table table : tables) {
            if (table.name.equals(params.table)) {
                return new Table[]{table};
            }
        }
        throw DBOperationException.notFound("No such table: " + params.table);
    }
}
