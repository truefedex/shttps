package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.operations.DBOperation;
import com.phlox.simpleserver.database.operations.DeleteOperation;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;

public class DBDeleteRequestHandler extends BaseDBRequestHandler {
    public DBDeleteRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
        super(database, config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_DELETE)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_DELETE});
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
            DeleteOperation.Params params = DeleteOperation.Params.parse(
                    request.urlEncodedPostParams.get("table"),
                    request.urlEncodedPostParams.get("filters"));
            int deletedRows = runInTransaction(database,
                    new DeleteOperation(config, authManager, params), user);
            return StandardResponses.OK(new JSONObject().put("deleted_rows", deletedRows).toString());
        } catch (Exception e) {
            return toResponse(e, "Failed to delete data: ");
        }
    }
}
