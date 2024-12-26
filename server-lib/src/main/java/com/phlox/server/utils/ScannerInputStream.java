package com.phlox.server.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

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
            try {
                return base.read();
            } catch (SocketTimeoutException e) {
                return -1;
            }
        } else {
            return backBuff[backBuffPosition--] & 0xff;
        }
    }

    public void writeBack(byte[] buf, int offset, int len) {
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
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean boundaryFound = readUntilDelimiter(new byte[]{0x0D, 0x0A}, output);
        String result = output.toString("utf-8");
        return ("".equals(result) && !boundaryFound) ? null : result;
    }
}
