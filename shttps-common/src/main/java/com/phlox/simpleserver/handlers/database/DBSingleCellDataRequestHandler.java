package com.phlox.simpleserver.handlers.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.operations.ReadCellOperation;
import com.phlox.simpleserver.utils.Holder;

public class DBSingleCellDataRequestHandler extends BaseDBRequestHandler {
    public DBSingleCellDataRequestHandler(Holder<Database> database, SHTTPSConfig config, com.phlox.simpleserver.auth.AuthManager authManager) {
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
            ReadCellOperation.Params operationParams = ReadCellOperation.Params.parse(
                    params.get("table"), params.get("column"), params.get("filters"));
            Database.CellDataStreamInfo cell = runInTransaction(database,
                    new ReadCellOperation(config, authManager, operationParams), user);

            if (cell.inputStream == null) {
                //the cell is null, or holds something other than text or a blob
                return StandardResponses.NO_CONTENT();
            }
            //the stream is the response body and stays open until the body has been sent; closing
            //it here would hand the client a stream that is already finished
            return new Response(cell.mimeType, cell.length, cell.inputStream);
        } catch (Exception e) {
            return toResponse(e, "");
        }
    }
}
