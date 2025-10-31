package com.phlox.simpleserver.database;

public interface DatabaseTransactionScope<T> {
    T execute(DatabaseOperations db) throws Exception;
}
