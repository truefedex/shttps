package com.phlox.simpleserver.handlers.database;

import com.phlox.server.responses.Response;
import com.phlox.simpleserver.database.TableDataSerializer;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.AbstractDataStreamer;

import org.jetbrains.annotations.NotNull;

import java.io.OutputStream;

/**
 * Answers with a table result that is written out as it is read, on a thread of its own.
 * <p>
 * The rows are therefore produced after the request handler has returned - and, for a result read
 * inside a transaction, after that transaction has been committed. That is allowed: a committed
 * transaction does not invalidate a cursor, only closing its connection does, and the connection
 * stays open until the last reader is finished with it.
 */
class TableDataResponseStreamer extends AbstractDataStreamer {
    private final @NotNull TableData data;
    private final @NotNull TableDataSerializer.Options options;

    private TableDataResponseStreamer(int bufferSize, @NotNull TableData data,
                                      @NotNull TableDataSerializer.Options options) {
        super(bufferSize);
        this.data = data;
        this.options = options;
    }

    @Override
    protected void generateData(OutputStream output) throws Exception {
        //closes the cursor on every path, including a failure halfway through the rows
        TableDataSerializer.streamTo(output, data, options);
    }

    /**
     * Builds the JSON response for {@code data}, taking ownership of the cursor: it is closed once
     * the body has been written, or immediately if the writing thread could not be started.
     */
    static Response respondWith(int bufferSize, @NotNull TableData data,
                                @NotNull TableDataSerializer.Options options) throws Exception {
        TableDataResponseStreamer streamer = new TableDataResponseStreamer(bufferSize, data, options);
        try {
            streamer.startDataGenerationThread();
        } catch (Exception e) {
            //the streamer closes the cursor when it is done with it, so if it never started,
            //nobody will - and an unclosed cursor keeps its database connection open
            data.close();
            throw e;
        }
        Response response = new Response(streamer.getInputStream());
        response.setContentType("application/json");
        return response;
    }
}
