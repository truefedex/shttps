package com.phlox.server.request;

import java.io.InputStream;

public interface RequestBody {
    InputStream open();
    byte[] asBytes();
}
