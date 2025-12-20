package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;

public class DBSchemaRequestHandler extends BaseDBRequestHandler {
    public static final String READ_SCHEMA_OPERATION = "READ_SCHEMA";

    public DBSchemaRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context,
                                  Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }

        String tableName = request.queryParams.get("table");

        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        if (checkIsForbidden(database, user, tableName != null ? tableName : "*",
                READ_SCHEMA_OPERATION, null, User.DBRights.READ_SCHEMA))
            return StandardResponses.FORBIDDEN();
        Table[] tables = database.getTables();
        if (tables != null) {
            if (tableName != null) {
                for (Table table : tables) {
                    if (table.name.equals(tableName)) {
                        return StandardResponses.OK(table.toJson().toString(), "application/json");
                    }
                }
                return StandardResponses.NOT_FOUND();
            }
            JSONArray json = new JSONArray();
            for (Table table : tables) {
                json.put(table.toJson());
            }
            return StandardResponses.OK(json.toString(), "application/json");
        }
        return StandardResponses.NOT_FOUND();
    }
}
