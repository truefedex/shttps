package com.phlox.simpleserver.database;

import java.io.InputStream;

public interface SHTTPSDatabaseFabric {
    Database createDatabase(String path) throws Exception;
    Database openDatabase(String path) throws Exception;
    Database importDatabase(InputStream inputStream) throws Exception;

    /**
     * Removes the database (and any platform-specific companion files like
     * SQLite journal/wal/shm) at {@code path}. Caller is responsible for
     * closing any open {@link Database} pointing at the same file before
     * calling this method.
     *
     * @return {@code true} if the database was removed (or did not exist),
     *         {@code false} otherwise.
     */
    boolean deleteDatabase(String path) throws Exception;
}
