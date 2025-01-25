package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DBSingleCellDataRequestHandler extends BaseDBRequestHandler{
    public DBSingleCellDataRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
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
        String column = params.get("column");
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

        InputStream stream = null;
        try {
            Database.CellDataStreamInfo cellDataStreamInfo = database.getSingleCellDataStream(table, column, filters, filtersArgs);
            if (cellDataStreamInfo == null) {
                return StandardResponses.NOT_FOUND();
            }
            stream = cellDataStreamInfo.inputStream;
            if (stream == null) {
                return StandardResponses.NO_CONTENT();//cell value is null or wrong type (only string or binary is supported)
            }

            return new Response(cellDataStreamInfo.mimeType, cellDataStreamInfo.length, stream);
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR(e.getMessage());
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }
}
