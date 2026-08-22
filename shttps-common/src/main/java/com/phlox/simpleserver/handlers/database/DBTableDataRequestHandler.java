package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.DBFilters;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.TableDataSerializer;
import com.phlox.simpleserver.database.operations.DBOperationException;
import com.phlox.simpleserver.database.operations.ReadTableOperation;
import com.phlox.simpleserver.database.operations.TableDataResult;
import com.phlox.simpleserver.utils.Holder;

public class DBTableDataRequestHandler extends BaseDBRequestHandler {
    private static final int RESPONSE_BUFFER_SIZE = 1024;

    public DBTableDataRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if ((!request.method.equals(Request.METHOD_GET)) &&
                !request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_POST});
        }
        MultiMap<String, String> params = readParams(context, request);

        Database database = currentDatabase();
        if (database == null) {
            return StandardResponses.NOT_FOUND();
        }
        User user = checkUser(context);
        try {
            ReadTableOperation.Params operationParams = new ReadTableOperation.Params(
                    params.get("table"),
                    params.get("columns"),
                    parseLong(params.get("offset"), "offset"),
                    parseLong(params.get("limit"), "limit"),
                    params.get("sort"),
                    params.get("sort-order"),
                    Boolean.parseBoolean(params.get("includeRowId")),
                    Boolean.parseBoolean(params.get("includeTotal")),
                    Boolean.parseBoolean(params.get("rowsAsObjects")),
                    DBFilters.parse(params.get("filters")));

            //the rights check and the read happen in the same transaction, so the data returned is
            //the data the check was made against
            TableDataResult result = runInTransaction(database,
                    new ReadTableOperation(config, authManager, operationParams), user);

            TableDataSerializer.Options options = new TableDataSerializer.Options()
                    .rowsAsObjects(operationParams.rowsAsObjects)
                    .total(result.total);
            return TableDataResponseStreamer.respondWith(RESPONSE_BUFFER_SIZE, result.data, options);
        } catch (Exception e) {
            return toResponse(e, "");
        }
    }

    private static Long parseLong(String value, String name) throws DBOperationException {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw DBOperationException.badRequest(name + " must be a number, got: " + value);
        }
    }
}
