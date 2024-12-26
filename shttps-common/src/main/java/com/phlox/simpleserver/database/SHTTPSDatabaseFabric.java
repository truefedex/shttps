package com.phlox.simpleserver.database;

import java.io.InputStream;

public interface SHTTPSDatabaseFabric {
    Database createDatabase(String path) throws Exception;
    Database openDatabase(String path) throws Exception;
    Database importDatabase(InputStream inputStream) throws Exception;
}
