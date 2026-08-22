package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.database.operations.ReadSchemaOperation;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;

public class DBSchemaRequestHandler extends BaseDBRequestHandler {
    public DBSchemaRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        String tableName = request.queryParams.get("table");

        Database database = currentDatabase();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        try {
            //the rights check and the schema read share one transaction
            Table[] tables = runInTransaction(database,
                    new ReadSchemaOperation(config, authManager, new ReadSchemaOperation.Params(tableName)), user);

            //asking about one table answers with that table, asking about none with all of them
            String json = tableName != null ? tables[0].toJson().toString()
                    : new JSONArray(toJsonList(tables)).toString();
            return StandardResponses.OK(json, "application/json");
        } catch (Exception e) {
            return toResponse(e, "");
        }
    }

    private static JSONArray toJsonList(Table[] tables) {
        JSONArray json = new JSONArray();
        for (Table table : tables) {
            json.put(table.toJson());
        }
        return json;
    }
}
