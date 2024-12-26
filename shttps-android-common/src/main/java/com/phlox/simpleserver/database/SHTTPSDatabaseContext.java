package com.phlox.simpleserver.database;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

public class SHTTPSDatabaseContext extends ContextWrapper {
    private final File databaseDirectory;

    public SHTTPSDatabaseContext(Context base, File databaseDirectory) {
        super(base);
        this.databaseDirectory = databaseDirectory;
    }

    @Override
    public File getDatabasePath(String name)  {
        return new File(databaseDirectory, name);
    }

    /* this version is called for android devices >= api-11 */
    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        return openOrCreateDatabase(name,mode, factory);
    }

    /* this version is called for android devices < api-11 */
    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
    }
}
