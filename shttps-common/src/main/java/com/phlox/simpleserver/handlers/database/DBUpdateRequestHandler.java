package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseOperations;
import com.phlox.simpleserver.database.DatabaseTransactionScope;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DBUpdateRequestHandler extends BaseDBRequestHandler {
    public static final String UPDATE_OPERATION = "UPDATE";

    public DBUpdateRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_PUT)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_PUT});
        }

        if (!config.isAllowDatabaseTableDataEditingApi()) {
            return StandardResponses.FORBIDDEN("Database table data editing API is disabled");
        }

        context.requestBodyReader.readRequestBody(request);
        String table = request.urlEncodedPostParams.get("table");
        if (table == null) {
            return StandardResponses.BAD_REQUEST("table parameter is required");
        }
        List<String> filters = new ArrayList<>();
        List<Object> filtersArgs = new ArrayList<>();
        String filtersJsonStr = request.urlEncodedPostParams.get("filters");
        JSONObject filtersJson = normalizeFilters(filtersJsonStr);
        JSONArray filtersJsonArray = filtersJson.getJSONArray("clauses");
        JSONArray filtersArgsJsonArray = filtersJson.getJSONArray("args");
        for (int i = 0; i < filtersJsonArray.length(); i++) {
            filters.add(filtersJsonArray.getString(i));
        }
        for (int i = 0; i < filtersArgsJsonArray.length(); i++) {
            filtersArgs.add(filtersArgsJsonArray.get(i));
        }
        JSONObject rowJson;
        try {
            String rowJsonStr = request.urlEncodedPostParams.get("values");
            rowJson = new JSONObject(rowJsonStr);
        } catch (Exception e) {
            return StandardResponses.BAD_REQUEST("Invalid JSON data: " + e.getMessage());
        }

        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        return database.runTransaction(db -> {
            if (checkIsForbidden(db, user, table, UPDATE_OPERATION, Map.of(
                    "values", rowJson.toString(),
                    "filters", filtersJson.toString()
            ), User.DBRights.UPDATE))
                return StandardResponses.FORBIDDEN();
            try {
                int updatedRows = db.update(table, rowJson,
                        filters.toArray(new String[0]), filtersArgs.toArray(new Object[0]));
                JSONObject responseJson = new JSONObject();
                responseJson.put("updated_rows", updatedRows);
                return StandardResponses.OK(responseJson.toString());
            } catch (Exception e) {
                return StandardResponses.INTERNAL_SERVER_ERROR("Failed to update data: " + e.getMessage());
            }
        });

    }
}
