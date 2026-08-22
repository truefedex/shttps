package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.operations.DBOperationException;

import org.json.JSONArray;
import org.json.JSONObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The WHERE part of a data request, as it travels over the wire:
 * <pre>
 * {"clauses": ["name?", "rowid∈3"], "args": ["%foo%", 12, 13, 14]}
 * </pre>
 * A clause is a column name followed by an operator suffix; the grammar and the SQL it turns into
 * live in {@link com.phlox.simpleserver.database.utils.DBUtils#buildSimpleWhereStatement}. This
 * class only carries the two arrays around and keeps their decoding in one place - it does not
 * interpret them.
 */
public final class DBFilters {
    public static final String FIELD_CLAUSES = "clauses";
    public static final String FIELD_ARGS = "args";

    private static final String[] NO_CLAUSES = new String[0];
    private static final Object[] NO_ARGS = new Object[0];

    public static final DBFilters EMPTY = new DBFilters(NO_CLAUSES, NO_ARGS);

    private final String[] clauses;
    private final Object[] args;

    private DBFilters(String[] clauses, Object[] args) {
        this.clauses = clauses;
        this.args = args;
    }

    /**
     * Decodes the {@code filters} request parameter. A missing parameter means "no filters" rather
     * than an error - every endpoint treats it that way.
     *
     * @throws DBOperationException of kind {@link DBOperationException.Kind#BAD_REQUEST} if the
     *         value is not the expected JSON shape. It used to escape as a JSONException and reach
     *         the client as a 500.
     */
    public static @NotNull DBFilters parse(@Nullable String filtersJsonStr) throws DBOperationException {
        if (filtersJsonStr == null) {
            return EMPTY;
        }
        JSONObject filtersJson;
        try {
            filtersJson = new JSONObject(filtersJsonStr);
        } catch (Exception e) {
            throw new DBOperationException(DBOperationException.Kind.BAD_REQUEST,
                    "Invalid filters JSON: " + e.getMessage(), e);
        }
        return of(filtersJson);
    }

    public static @NotNull DBFilters of(@NotNull JSONObject filtersJson) throws DBOperationException {
        if (!filtersJson.has(FIELD_CLAUSES) || !filtersJson.has(FIELD_ARGS) ||
                !(filtersJson.get(FIELD_CLAUSES) instanceof JSONArray) ||
                !(filtersJson.get(FIELD_ARGS) instanceof JSONArray)) {
            throw new DBOperationException(DBOperationException.Kind.BAD_REQUEST,
                    "filters JSON must contain 'clauses' and 'args' array fields");
        }
        JSONArray clausesJson = filtersJson.getJSONArray(FIELD_CLAUSES);
        JSONArray argsJson = filtersJson.getJSONArray(FIELD_ARGS);

        String[] clauses = clausesJson.length() == 0 ? NO_CLAUSES : new String[clausesJson.length()];
        for (int i = 0; i < clausesJson.length(); i++) {
            Object clause = clausesJson.get(i);
            if (!(clause instanceof String)) {
                throw new DBOperationException(DBOperationException.Kind.BAD_REQUEST,
                        "filters clause #" + i + " is not a string");
            }
            clauses[i] = (String) clause;
        }
        Object[] args = argsJson.length() == 0 ? NO_ARGS : new Object[argsJson.length()];
        for (int i = 0; i < argsJson.length(); i++) {
            args[i] = argsJson.get(i);
        }
        return new DBFilters(clauses, args);
    }

    public boolean isEmpty() {
        return clauses.length == 0;
    }

    /** The clause array in the shape the {@link DatabaseOperations} methods take. */
    public @NotNull String[] clauses() {
        return clauses.length == 0 ? NO_CLAUSES : clauses.clone();
    }

    /** The bind arguments in the shape the {@link DatabaseOperations} methods take. */
    public @NotNull Object[] args() {
        return args.length == 0 ? NO_ARGS : args.clone();
    }

    /**
     * The original wire shape, which is what the rights evaluator gets as the {@code filters}
     * operation parameter and what user-authored rule expressions see as {@code :param.filters}.
     */
    public @NotNull JSONObject toJson() {
        return new JSONObject()
                .put(FIELD_CLAUSES, new JSONArray(clauses))
                .put(FIELD_ARGS, new JSONArray(args));
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
