package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.AbstractDataStreamer;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONArray;

import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

public class DBCustomSQLRequestHandler extends BaseDBRequestHandler {
    public DBCustomSQLRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        super(database, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        Database database = this.database.get();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (!config.isAllowDatabaseCustomSqlRemoteApi()) {
            return StandardResponses.FORBIDDEN("Database custom SQL remote API is disabled");
        }
        //TODO: check if user has permission to execute custom SQL
        int limit = request.queryParams.containsKey("limit") ?
                Integer.parseInt(request.queryParams.get("limit")) : 100;
        int offset = request.queryParams.containsKey("offset") ?
                Integer.parseInt(request.queryParams.get("offset")) : 0;
        boolean includeColumnNames = request.queryParams.containsKey("include-names") &&
                Boolean.parseBoolean(request.queryParams.get("include-names"));
        requestParser.parseRequestBody(request);
        String sql = new String(request.body.asBytes(), StandardCharsets.UTF_8);
        try {
            TableData result = database.execute(sql);
            if (result == null) {
                return new TextResponse(200, "OK", "{}");
            }
            SQLResponseStreamer streamer = new SQLResponseStreamer(result, offset, limit, includeColumnNames);
            streamer.startDataGenerationThread();
            Response response = new Response(streamer.getInputStream());
            response.setContentType("application/json");
            return response;
        } catch (Exception e) {
            return new TextResponse(420, "Method Failure", e.getMessage());
        }
    }

    private static class SQLResponseStreamer extends AbstractDataStreamer {
        private final TableData responseData;
        private final int offset;
        private final int limit;
        private final boolean includeColumnNames;

        public SQLResponseStreamer(TableData responseData, int offset, int limit,
                                   boolean includeColumnNames) {
            super(1024 * 1024);
            this.responseData = responseData;
            this.offset = offset;
            this.limit = limit;
            this.includeColumnNames = includeColumnNames;
        }
        @Override
        protected void generateData(PipedOutputStream output) throws Exception {
            long total = responseData.count();
            String responsePrefix = "{\"offset\":" + offset +
                    ",\"limit\":" + limit +
                    ",\"total\":" + total + ",";
            if (includeColumnNames) {
                JSONArray columnNames = new JSONArray(responseData.getColumnNames());
                responsePrefix += "\"columns\":" + columnNames + ",";
            }
            responsePrefix += "\"data\":[";
            output.write(responsePrefix.getBytes(StandardCharsets.UTF_8));

            if (offset > 0) {
                boolean canSkip = responseData.skip(offset);
                if (!canSkip) {
                    output.write("]}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            int count = 0;
            while (responseData.next() && count < limit) {
                if (count > 0) {
                    output.write(",".getBytes(StandardCharsets.UTF_8));
                }
                output.write(responseData.currentRowToJson().toString().getBytes(StandardCharsets.UTF_8));
                count++;
            }
            output.write("]}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
