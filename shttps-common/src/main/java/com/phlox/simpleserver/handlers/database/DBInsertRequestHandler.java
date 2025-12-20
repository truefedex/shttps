package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONObject;

import java.util.Map;

public class DBInsertRequestHandler extends BaseDBRequestHandler {
    public static final String INSERT_OPERATION = "INSERT";

    public DBInsertRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }

        if (!config.isAllowDatabaseTableDataEditingApi()) {
            return StandardResponses.FORBIDDEN("Database table data editing API is disabled");
        }

        context.requestBodyReader.readRequestBody(request);
        String table = request.urlEncodedPostParams.get("table");
        if (table == null) {
            return StandardResponses.BAD_REQUEST("table parameter is required");
        }
        JSONObject rowJson;
        try {
            String rowJsonStr = request.urlEncodedPostParams.get("values");
            rowJson = new JSONObject(rowJsonStr);
        } catch (Exception e) {
            return StandardResponses.BAD_REQUEST("Invalid JSON data: " + e.getMessage());
        }

        User user = checkUser(context);
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        return database.runTransaction(db -> {
            if (checkIsForbidden(db, user, table, INSERT_OPERATION, Map.of(
                    "values", rowJson.toString()
            ), User.DBRights.CREATE))
                return StandardResponses.FORBIDDEN();
            try {
                long id = db.insert(table, rowJson);
                JSONObject responseJson = new JSONObject();
                responseJson.put("generated_id", id);
                return StandardResponses.OK(responseJson.toString(), "application/json");
            } catch (Exception e) {
                return StandardResponses.INTERNAL_SERVER_ERROR("Failed to insert data: " + e.getMessage());
            }
        });
    }
}
