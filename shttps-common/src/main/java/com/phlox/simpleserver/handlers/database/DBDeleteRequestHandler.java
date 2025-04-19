package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DBDeleteRequestHandler extends BaseDBRequestHandler {
    public DBDeleteRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_DELETE)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_DELETE});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (!config.isAllowDatabaseTableDataEditingApi()) {
            return StandardResponses.FORBIDDEN("Database table data editing API is disabled");
        }
        //TODO: check if user has permission to delete data
        requestBodyReader.readRequestBody(request);
        String table = request.urlEncodedPostParams.get("table");
        if (table == null) {
            return StandardResponses.BAD_REQUEST("table parameter is required");
        }
        List<String> filters = new ArrayList<>();
        List<Object> filtersArgs = new ArrayList<>();
        String filtersJsonStr = request.urlEncodedPostParams.get("filters");
        if (filtersJsonStr != null) {
            JSONObject filtersJson = new JSONObject(filtersJsonStr);
            JSONArray filtersJsonArray = filtersJson.getJSONArray("clauses");
            JSONArray filtersArgsJsonArray = filtersJson.getJSONArray("args");
            for (int i = 0; i < filtersJsonArray.length(); i++) {
                filters.add(filtersJsonArray.getString(i));
            }
            for (int i = 0; i < filtersArgsJsonArray.length(); i++) {
                filtersArgs.add(filtersArgsJsonArray.get(i));
            }
        }
        try {
            int deletedRows = database.delete(table,
                    filters.toArray(new String[0]), filtersArgs.toArray(new Object[0]));
            return StandardResponses.OK("{\"deleted_rows\":" + deletedRows + "}");
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Failed to delete data: " + e.getMessage());
        }
    }
}
