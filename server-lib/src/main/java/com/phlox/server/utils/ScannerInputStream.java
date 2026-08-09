package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ScannerInputStream extends InputStream {
    InputStream base;
    byte[] backBuff = new byte[16];
    int backBuffPosition = -1;

    public ScannerInputStream(InputStream base) {
        this.base = base;
    }

    @Override
    public int read() throws IOException {
        if (backBuffPosition == -1) {
            return base.read();
        } else {
            return backBuff[backBuffPosition--] & 0xff;
        }
    }

    /**
     * Bulk read. Without this override java.io.InputStream falls back to a per-byte
     * loop, which makes every raw request body (PUT, WebDAV PUT, non-form POST) cost
     * one call per byte instead of one per buffer. Note that pushed-back bytes are
     * served first and are never mixed with fresh data from the base stream, so this
     * may return a short read - which is legal and expected by callers.
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (backBuffPosition == -1) {
            return base.read(b, off, len);
        }
        //back buffer holds bytes in reverse order, top of the "stack" comes out first
        int count = Math.min(len, backBuffPosition + 1);
        for (int i = 0; i < count; i++) {
            b[off + i] = backBuff[backBuffPosition--];
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        return (backBuffPosition + 1) + base.available();
    }

    //package-private instead of private so that tests can push bytes back directly:
    //the parsing loops below always drain the back buffer before they return, so there
    //is no public API that leaves it non-empty
    void writeBack(byte[] buf, int offset, int len) {
        int backBuffDataSize = backBuffPosition + 1;
        if (backBuff.length < (len + backBuffDataSize)) {
            byte[] newArray = new byte[len + backBuffDataSize];
            if (backBuffPosition != -1) {
                System.arraycopy(backBuff, 0, newArray, 0, backBuffDataSize);
            }
            backBuff = newArray;
        }
        for (int i = len + offset - 1; i >= offset; i--) {
            backBuffPosition++;
            backBuff[backBuffPosition] = buf[i];
        }
    }

    public boolean readUntilDelimiter(byte[] delimiter, OutputStream output) throws IOException {
        byte[] collector = new byte[delimiter.length];
        int readenByte;
        int collectorPosition = 0;
        boolean boundaryFound = false;
        while ((readenByte = read()) != -1) {
            if (readenByte == delimiter[collectorPosition]) {
                collector[collectorPosition] = (byte) readenByte;
                collectorPosition++;
                if (collectorPosition == collector.length) {
                    boundaryFound = true;
                    break;
                }
            } else if (collectorPosition > 0) {
                //collected bytes are not boundary but the boundary may still start somewhere
                //in the middle of the collector so we should return back and check
                collector[collectorPosition] = (byte) readenByte;//add wrong byte also to collector just for convenience to copy all back
                output.write(collector[0]);
                writeBack(collector, 1, collectorPosition);
                collectorPosition = 0;
            } else {
                output.write(readenByte);
            }
        }
        return boundaryFound;
    }

    public String nextLine() throws IOException {
        return nextLine("ASCII");
    }

    public String nextLine(String charset) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean boundaryFound = readUntilDelimiter(new byte[]{0x0D, 0x0A}, output);
        String result = output.toString(charset);
        return ("".equals(result) && !boundaryFound) ? null : result;
    }
}
