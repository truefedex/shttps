package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.AbstractDataStreamer;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class DBCustomSQLRequestHandler extends BaseDBRequestHandler {
    public static final String CUSTOM_SQL_DATABASE_SUBJECT_CONSTANT = "*";
    public static final String CUSTOM_SQL_DATABASE_OPERATION = "EXECUTE";

    public DBCustomSQLRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        if (!config.isAllowDatabaseCustomSqlRemoteApi()) {
            return StandardResponses.FORBIDDEN("Database custom SQL remote API is disabled");
        }
        int limit = request.queryParams.containsKey("limit") ?
                Integer.parseInt(request.queryParams.get("limit")) : 100;
        int offset = request.queryParams.containsKey("offset") ?
                Integer.parseInt(request.queryParams.get("offset")) : 0;
        boolean includeColumnNames = request.queryParams.containsKey("includeNames") &&
                Boolean.parseBoolean(request.queryParams.get("includeNames"));
        context.requestBodyReader.readRequestBody(request);
        final String sql = new String(request.body.asBytes(), StandardCharsets.UTF_8);
        if (sql.trim().isEmpty()) {
            return StandardResponses.BAD_REQUEST("SQL query is empty");
        }

        User user = checkUser(context);
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }

        return database.runTransaction(db -> {
            if (checkIsForbidden(db, user, CUSTOM_SQL_DATABASE_SUBJECT_CONSTANT,
                    CUSTOM_SQL_DATABASE_OPERATION, null,
                    User.DBRights.EXEC_SQL)) return StandardResponses.FORBIDDEN();

            List<String> sqlStatements = Utils.splitSqlStatementsSQLite(sql);
            try {
                // Only the last statement can return data
                // execute previous statements first
                for (int i = 0; i < sqlStatements.size() - 1; i++) {
                    String stmt = sqlStatements.get(i);
                    TableData data = db.query(stmt);
                    if (data != null) {
                        data.close();
                    }
                }
                // now execute the last statement and return its data
                String lastSQLStat = sqlStatements.get(sqlStatements.size() - 1);

                TableData result = db.query(lastSQLStat);
                if (result == null) {
                    return new TextResponse(200, "OK", "{}");
                }
                SQLResponseStreamer streamer = new SQLResponseStreamer(result, offset, limit,
                        includeColumnNames);
                streamer.startDataGenerationThread();
                Response response = new Response(streamer.getInputStream());
                response.setContentType("application/json");
                return response;
            } catch (Exception e) {
                return new TextResponse(420, "Method Failure", e.getMessage());
            }
        });
    }

    private static class SQLResponseStreamer extends AbstractDataStreamer {
        private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());
        private final TableData responseData;
        private final int offset;
        private final int limit;
        private final boolean includeColumnNames;

        public SQLResponseStreamer(TableData responseData, int offset, int limit,
                                   boolean includeColumnNames) {
            super(1024 * 1024);
            this.responseData = responseData;
            this.offset = offset;
            this.limit = limit;
            this.includeColumnNames = includeColumnNames;
        }

        @Override
        protected void generateData(OutputStream output) throws Exception {
            try {
                String responsePrefix = "{\"offset\":" + offset +
                        ",\"limit\":" + limit;
                responsePrefix += ",";
                if (includeColumnNames) {
                    JSONArray columnNames = new JSONArray(responseData.getColumnNames());
                    responsePrefix += "\"columns\":" + columnNames + ",";
                }
                responsePrefix += "\"data\":[";
                output.write(responsePrefix.getBytes(StandardCharsets.UTF_8));

                if (offset > 0) {
                    boolean canSkip = responseData.skip(offset);
                    if (!canSkip) {
                        output.write("]}".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
                int count = 0;
                while (responseData.next() && count < limit) {
                    if (count > 0) {
                        output.write(",".getBytes(StandardCharsets.UTF_8));
                    }
                    output.write(responseData.currentRowToJson().toString().getBytes(StandardCharsets.UTF_8));
                    count++;
                }
                output.write("]}".getBytes(StandardCharsets.UTF_8));
            } finally {
                try {
                    responseData.close();
                } catch (Exception e) {
                    logger.stackTrace(e);
                }
            }
        }
    }
}
