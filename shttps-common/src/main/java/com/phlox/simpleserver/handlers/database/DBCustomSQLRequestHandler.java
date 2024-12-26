package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

public class DBCustomSQLRequestHandler extends BaseDBRequestHandler {
    public DBCustomSQLRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (!config.isAllowDatabaseCustomSqlRemoteApi()) {
            return StandardResponses.FORBIDDEN("Database custom SQL remote API is disabled");
        }
        //TODO: check if user has permission to execute custom SQL
        String resultType = request.queryParams.get("result");
        if (resultType == null) {
            resultType = "stats";
        }
        String sql = new String(request.body.asBytes(), "UTF-8");
        try {
            switch (resultType) {
                case "stats":
                    Database.ExecuteResult result = database.execute(sql);
                    return StandardResponses.OK("{\"updated_rows\":" + result.updatedRows + ",\"inserted_id\":" + result.generatedId + "}");
                case "data":
                default:
                    return StandardResponses.OK(database.query(sql).toJson().toString(), "application/json");
            }
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Failed to execute custom SQL: " + e.getMessage());
        }
    }
}
