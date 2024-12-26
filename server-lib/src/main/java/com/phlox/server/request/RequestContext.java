package com.phlox.server.request;

import com.phlox.server.SimpleHttpServer;
import java.util.HashMap;

public class RequestContext {
    public final SimpleHttpServer server;
    public final HashMap<String, Object> data = new HashMap<>();

    public RequestContext(SimpleHttpServer server) {
        this.server = server;
    }
}
