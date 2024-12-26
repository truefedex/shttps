package com.phlox.simpleserver.handlers.database;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.Holder;

public abstract class BaseDBRequestHandler implements RequestHandler {
    protected final Holder<Database> database;
    protected final SHTTPSConfig config;

    public BaseDBRequestHandler(Holder<Database> database, SHTTPSConfig config) {
        this.database = database;
        this.config = config;
    }
}
