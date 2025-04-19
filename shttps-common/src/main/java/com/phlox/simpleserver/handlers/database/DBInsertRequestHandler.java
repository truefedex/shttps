package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

public class DBInsertRequestHandler extends BaseDBRequestHandler {
    public DBInsertRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (!config.isAllowDatabaseTableDataEditingApi()) {
            return StandardResponses.FORBIDDEN("Database table data editing API is disabled");
        }
        //TODO: check if user has permission to insert data
        requestBodyReader.readRequestBody(request);
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
        try {
            long id = database.insert(table, rowJson);
            JSONObject responseJson = new JSONObject();
            responseJson.put("generated_id", id);
            return StandardResponses.OK(responseJson.toString(), "application/json");
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Failed to insert data: " + e.getMessage());
        }
    }
}
