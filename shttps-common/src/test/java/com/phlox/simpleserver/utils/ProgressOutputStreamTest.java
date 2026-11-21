package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

public class ProgressOutputStreamTest {

    @Test
    public void aQuotaFailureOnCloseReachesTheCaller() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProgressOutputStream out = new ProgressOutputStream(bytes, 1024 * 1024, new ProgressOutputStream.WriteProgressListener() {
            @Override
            public void onChunkWritten(long totalBytes, long chunkSize) {
                throw new IllegalStateException("no space");
            }

            @Override
            public void onStreamClosed(long totalBytes, Exception withException) {
                assertEquals("no space", withException.getMessage());
            }
        });

        assertThrows(RuntimeException.class, () -> {
            out.write(new byte[]{1, 2, 3});
            out.close();
        });
    }
}
