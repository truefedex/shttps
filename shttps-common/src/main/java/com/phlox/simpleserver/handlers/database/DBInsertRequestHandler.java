package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.operations.DBOperation;
import com.phlox.simpleserver.database.operations.InsertOperation;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

public class DBInsertRequestHandler extends BaseDBRequestHandler {
    public DBInsertRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
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
            DBOperation.checkTableDataEditingEnabled(config);
            InsertOperation.Params params = InsertOperation.Params.parse(
                    request.urlEncodedPostParams.get("table"),
                    request.urlEncodedPostParams.get("values"));
            JSONObject result = runInTransaction(database,
                    new InsertOperation(config, authManager, params), user);
            return StandardResponses.OK(result.toString(), "application/json");
        } catch (Exception e) {
            return toResponse(e, "Failed to insert data: ");
        }
    }
}
