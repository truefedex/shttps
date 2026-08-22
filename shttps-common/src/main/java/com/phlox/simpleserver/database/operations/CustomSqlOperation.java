package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DeadlineExceededException;
import com.phlox.simpleserver.database.TransactionAbortedException;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.SqlStatementSplitter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Runs arbitrary SQL. The body may hold several statements; they all run in the enclosing
 * transaction, and only the last one can return rows.
 */
public class CustomSqlOperation extends DBOperation<TableData> {
    /** Custom SQL is not about one table, so rules for it are written against this subject. */
    public static final String SUBJECT = "*";
    public static final String OPERATION = "EXECUTE";

    public static class Params {
        public final @Nullable String sql;

        public Params(@Nullable String sql) {
            this.sql = sql;
        }
    }

    private final @NotNull Params params;

    public CustomSqlOperation(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager,
                              @NotNull Params params) {
        super(config, authManager);
        this.params = params;
    }

    /**
     * @return an open cursor over the last statement's rows, or null if it produced none (an
     *         INSERT, a DDL statement). The caller owns and must close a returned cursor.
     */
    @Override
    public TableData execute(@NotNull DatabaseOperations db, @Nullable User user) throws Exception {
        checkCustomSqlEnabled();
        if (params.sql == null || params.sql.trim().isEmpty()) {
            throw DBOperationException.badRequest("SQL query is empty");
        }

        checkAllowed(db, user, SUBJECT, OPERATION, null, User.DBRights.EXEC_SQL);

        List<String> statements = SqlStatementSplitter.split(params.sql);
        if (statements.isEmpty()) {
            throw DBOperationException.badRequest("SQL query is empty");
        }

        try {
            //only the last statement can return data, so the earlier ones are run and discarded
            for (int i = 0; i < statements.size() - 1; i++) {
                TableData data = db.query(statements.get(i));
                if (data != null) {
                    data.close();
                }
            }
            return db.query(statements.get(statements.size() - 1));
        } catch (TransactionAbortedException | DeadlineExceededException e) {
            //the transaction was ended from outside, which is not the SQL's fault - and a batch of
            //statements is the most likely thing to be cut short part way through
            throw e;
        } catch (Exception e) {
            //a rejected statement is the database's answer to the user's SQL, not a server fault
            throw DBOperationException.failed(e.getMessage(), e);
        }
    }
}
