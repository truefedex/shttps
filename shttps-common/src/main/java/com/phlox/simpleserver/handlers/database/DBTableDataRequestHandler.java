package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DBTableDataRequestHandler extends BaseDBRequestHandler {
    public DBTableDataRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        //params: table, columns, offset, limit, sort, sortDir, filters, filterArgs
        if ((!request.method.equals(Request.METHOD_GET)) &&
                !request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        Map<String, String> params;
        if (request.method.equals(Request.METHOD_GET)) {
            params = request.queryParams;
        } else {
            requestParser.parseRequestBody(request);
            params = request.urlEncodedPostParams;
        }
        String table = params.get("table");
        if (table == null) {
            return StandardResponses.BAD_REQUEST("table parameter is required");
        }
        String columns = params.get("columns");
        String offset = params.get("offset");
        String limit = params.get("limit");
        String sort = params.get("sort");
        String sortDir = params.get("sort-order");
        String includeRowIdStr = params.get("includeRowId");

        List<String> filters = new ArrayList<>();
        List<Object> filtersArgs = new ArrayList<>();
        String filtersJsonStr = params.get("filters");
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
        TableData tableData;
        try {
            tableData = database.getTableDataSecure(table, columns != null ? columns.split(",") : null,
                    offset != null ? Long.parseLong(offset) : null, limit != null ? Long.parseLong(limit) : null,
                    filters.toArray(new String[0]), filtersArgs.toArray(new Object[0]),
                    sort, sortDir != null && sortDir.equalsIgnoreCase("desc"),
                    includeRowIdStr != null && includeRowIdStr.equalsIgnoreCase("true"));
        } catch (SecurityException e) {
            return StandardResponses.FORBIDDEN(e.getMessage());
        } catch (IllegalArgumentException e) {
            return StandardResponses.BAD_REQUEST(e.getMessage());
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR(e.getMessage());
        }

        return StandardResponses.OK(tableData.toJson().toString(), "application/json");
    }
}
