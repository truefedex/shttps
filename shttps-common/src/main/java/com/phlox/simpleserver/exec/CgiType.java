package com.phlox.simpleserver.exec;

import org.json.JSONException;
import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import proguard.annotation.Keep;

public class CgiType {
    public static final String FIELD_EXTENSION = "extension";
    public static final String FIELD_MODE = "mode";
    public static final String FIELD_EXECUTE_WITH = "executeWith";
    public static final String FIELD_EXECUTION_TIMEOUT = "executionTimeout";

    @Keep
    public enum Mode {CGI, SIMPLE}

    public final @NotNull String extension;
    public final @NotNull Mode mode;
    public final @Nullable String executeWith;
    public final @Nullable Integer executionTimeout;

    public CgiType(String extension, @NotNull Mode mode, @Nullable String executeWith, @Nullable Integer executionTimeout) {
        this.extension = extension;
        this.mode = mode;
        this.executeWith = executeWith;
        this.executionTimeout = executionTimeout;
    }

    public @NotNull JSONObject serialize() {
        //JSONObject.put removes a key whose value is null, so an omitted interpreter or timeout
        //is the stored form of "not set". deserialize has to accept that shape.
        JSONObject object = new JSONObject()
                .put(FIELD_EXTENSION, this.extension)
                .put(FIELD_MODE, this.mode.name());
        if (this.executeWith != null) {
            object.put(FIELD_EXECUTE_WITH, this.executeWith);
        }
        if (this.executionTimeout != null) {
            object.put(FIELD_EXECUTION_TIMEOUT, this.executionTimeout);
        }
        return object;
    }

    public static @NotNull CgiType deserialize(@NotNull JSONObject object) throws JSONException {
        //isNull is true both when the key was never written and when it is JSON null.
        //getString/getInt throw JSONException in either case, which is what crashed the CGI list
        //after saving a type that has no interpreter.
        String executeWith = object.isNull(FIELD_EXECUTE_WITH) ? null : object.getString(FIELD_EXECUTE_WITH);
        Integer executionTimeout = object.isNull(FIELD_EXECUTION_TIMEOUT) ? null
                : Integer.valueOf(object.getInt(FIELD_EXECUTION_TIMEOUT));
        return new CgiType(object.getString(FIELD_EXTENSION), Mode.valueOf(object.getString(FIELD_MODE)),
                executeWith, executionTimeout);
    }
}
