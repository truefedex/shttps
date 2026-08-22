package com.phlox.simpleserver.database.operations;

import com.phlox.simpleserver.database.model.TableData;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An open cursor plus the total row count behind it, when one was asked for.
 * <p>
 * The cursor is handed over open on purpose: streaming it out row by row and reading it in full are
 * both legitimate, and only the caller knows which one its transport can do. Whoever receives this
 * owns the cursor and has to close it -
 * {@link com.phlox.simpleserver.database.TableDataSerializer} does that for you.
 */
public class TableDataResult {
    public final @NotNull TableData data;
    /** The row count ignoring offset/limit, or null if it was not requested. */
    public final @Nullable Long total;

    public TableDataResult(@NotNull TableData data, @Nullable Long total) {
        this.data = data;
        this.total = total;
    }
}
