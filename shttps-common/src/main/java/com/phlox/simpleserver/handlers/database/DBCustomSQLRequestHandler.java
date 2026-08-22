package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.TableDataSerializer;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.database.operations.CustomSqlOperation;
import com.phlox.simpleserver.database.operations.DBOperation;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.utils.Holder;

import java.nio.charset.StandardCharsets;

public class DBCustomSQLRequestHandler extends BaseDBRequestHandler {
    private static final int RESPONSE_BUFFER_SIZE = 1024 * 1024;
    private static final int DEFAULT_LIMIT = 100;
    /**
     * The answer to SQL the database rejected. Not a standard status code, but it is the one this
     * endpoint has always used and the web console tells it apart from a server fault.
     */
    private static final int CODE_SQL_FAILED = 420;
    private static final String PHRASE_SQL_FAILED = "Method Failure";

    public DBCustomSQLRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        context.requestBodyReader.readRequestBody(request);

        Database database = currentDatabase();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        try {
            //asked before the body is decoded: a disabled feature answers the same either way
            DBOperation.checkCustomSqlEnabled(config);
            //this endpoint pages in the response rather than in SQL: the statement runs whole and
            //the serializer skips and counts rows as it writes them out
            int limit = parseInt(request.queryParams.get("limit"), DEFAULT_LIMIT, "limit");
            int offset = parseInt(request.queryParams.get("offset"), 0, "offset");
            boolean includeColumnNames = Boolean.parseBoolean(request.queryParams.get("includeNames"));

            String sql = new String(request.body.asBytes(), StandardCharsets.UTF_8);
            TableData result = runInTransaction(database,
                    new CustomSqlOperation(config, authManager, new CustomSqlOperation.Params(sql)), user);
            if (result == null) {
                //the last statement produced no rows - an INSERT, or DDL
                return new TextResponse(200, "OK", "{}");
            }
            TableDataSerializer.Options options = new TableDataSerializer.Options()
                    .paging(offset, limit)
                    .includeColumnNames(includeColumnNames);
            return TableDataResponseStreamer.respondWith(RESPONSE_BUFFER_SIZE, result, options);
        } catch (DBOperationException e) {
            if (e.kind == DBOperationException.Kind.FAILED) {
                return new TextResponse(CODE_SQL_FAILED, PHRASE_SQL_FAILED, e.getMessage());
            }
            return toResponse(e);
        } catch (Exception e) {
            return toResponse(e, "");
        }
    }

    private static int parseInt(String value, int defaultValue, String name) throws DBOperationException {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw DBOperationException.badRequest(name + " must be a number, got: " + value);
        }
    }
}
