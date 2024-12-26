package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.Table;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;

public class DBSchemaRequestHandler extends BaseDBRequestHandler {
    public DBSchemaRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context,
                                  Request request,
                                  RequestParser requestParser) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        String tableName = request.queryParams.get("table");
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
