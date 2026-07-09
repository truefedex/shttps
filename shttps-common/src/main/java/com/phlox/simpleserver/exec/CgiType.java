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
        return new JSONObject()
                .put(FIELD_EXTENSION, this.extension)
                .put(FIELD_MODE, this.mode.name())
                .put(FIELD_EXECUTE_WITH, this.executeWith)
                .put(FIELD_EXECUTION_TIMEOUT, this.executionTimeout);
    }

    public static @NotNull CgiType deserialize(@NotNull JSONObject object) throws JSONException {
        Integer executionTimeout = object.has(FIELD_EXECUTION_TIMEOUT) ? Integer.valueOf(object.getInt(FIELD_EXECUTION_TIMEOUT)) : null;
        return new CgiType(object.getString(FIELD_EXTENSION), CgiType.Mode.valueOf(object.getString(FIELD_MODE)), object.getString(FIELD_EXECUTE_WITH), executionTimeout);
    }
}
