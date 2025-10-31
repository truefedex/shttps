package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.AbstractDataStreamer;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DBTableDataRequestHandler extends BaseDBRequestHandler {
    public DBTableDataRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if ((!request.method.equals(Request.METHOD_GET)) &&
                !request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_POST});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        if (checkIsForbidden(user, User.DBRights.READ)) return StandardResponses.FORBIDDEN("Insufficient rights");
        Map<String, String> params;
        if (request.method.equals(Request.METHOD_GET)) {
            params = request.queryParams;
        } else {
            context.requestBodyReader.readRequestBody(request);
            params = request.urlEncodedPostParams;
        }
        String table = params.get("table");
        if (table == null) {
            return StandardResponses.BAD_REQUEST("table parameter is required");
        }
        String columns = params.get("columns");
        String offsetParam = params.get("offset");
        String limitParam = params.get("limit");
        String sort = params.get("sort");
        String sortDir = params.get("sort-order");
        boolean includeRowId = Boolean.parseBoolean(params.get("includeRowId"));
        boolean rowsAsObjects = Boolean.parseBoolean(params.get("rowsAsObjects"));
        boolean includeTotal = Boolean.parseBoolean(params.get("includeTotal"));

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

        Long offset = offsetParam != null ? Long.parseLong(offsetParam) : null;
        Long limit = limitParam != null ? Long.parseLong(limitParam) : null;
        Holder<Long> outTotal = includeTotal ? new Holder<>(0L) : null;

        try {
            TableData tableData = database.getTableDataSecure(table, columns != null ? columns.split(",") : null,
                    offset, limit,
                    filters.toArray(new String[0]), filtersArgs.toArray(new Object[0]),
                    sort, sortDir != null && sortDir.equalsIgnoreCase("desc"),
                    includeRowId, outTotal);
            SQLResponseStreamer streamer = new SQLResponseStreamer(tableData,
                    rowsAsObjects, outTotal);
            streamer.startDataGenerationThread();
            Response response = new Response(streamer.getInputStream());
            response.setContentType("application/json");
            return response;
        } catch (SecurityException e) {
            return StandardResponses.FORBIDDEN(e.getMessage());
        } catch (IllegalArgumentException e) {
            return StandardResponses.BAD_REQUEST(e.getMessage());
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR(e.getMessage());
        }
    }

    private static class SQLResponseStreamer extends AbstractDataStreamer {
        private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());
        private final TableData responseData;
        private final boolean rowsAsObjects;
        private final Holder<Long> includeTotal;

        public SQLResponseStreamer(TableData responseData, boolean rowsAsObjects, Holder<Long> includeTotal) {
            super(1024);
            this.responseData = responseData;
            this.rowsAsObjects = rowsAsObjects;
            this.includeTotal = includeTotal;
        }

        @Override
        protected void generateData(OutputStream output) throws Exception {
            try {
                String responsePrefix;
                if (includeTotal != null) {
                    long total = includeTotal.get();
                    responsePrefix = "{\"total\":" + total + ",";
                } else {
                    responsePrefix = "{";
                }
                responsePrefix += "\"data\":[";
                output.write(responsePrefix.getBytes(StandardCharsets.UTF_8));

                int count = 0;
                while (responseData.next()) {
                    if (count > 0) {
                        output.write(",".getBytes(StandardCharsets.UTF_8));
                    }
                    String rowStr = rowsAsObjects ?
                            responseData.currentRowToJsonObject().toString() :
                            responseData.currentRowToJson().toString();
                    output.write(rowStr.getBytes(StandardCharsets.UTF_8));
                    count++;
                }
                output.write("]}".getBytes(StandardCharsets.UTF_8));
            } finally {
                try {
                    responseData.close();
                } catch (Exception e) {
                    logger.stackTrace(e);
                }
            }
        }
    }
}
