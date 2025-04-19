package com.phlox.server.utils;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public final class Utils {
    private Utils() {}

    public static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        while (true) {
            int count = input.read(buffer);
            if (count == -1) {
                break;
            }
            output.write(buffer, 0, count);
        }
    }

    public static void copyStream(InputStream input, OutputStream output, long amount) throws IOException {
        byte[] buffer = new byte[1024];
        long totalRead = 0;
        while (totalRead < amount) {
            int maxToRead = (int) Math.min(buffer.length, amount - totalRead);
            int count = input.read(buffer, 0, maxToRead);
            if (count == -1) {
                break;
            }
            output.write(buffer, 0, count);
            totalRead += count;
        }
    }

    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyStream(input, output);
        return output.toByteArray();
    }

    public static boolean copyFileOrDir(File src, File dst) {
        if (src.isDirectory()) {
            if ((!dst.exists()) && !dst.mkdirs()) {
                return false;
            }
            boolean success = true;
            File[] childs = src.listFiles();
            if (childs != null) {
                for (File f: childs) {
                    success &= copyFileOrDir(f, new File(dst, f.getName()));
                }
            }
            return success;
        } else {
            try (InputStream input = new BufferedInputStream(new FileInputStream(src));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(dst))) {
                com.phlox.server.utils.Utils.copyStream(input, output);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    public static boolean moveFileOrDir(File src, File dst) {
        if (src.renameTo(dst)) {
            return true;
        }
        if (src.isDirectory()) {
            if ((!dst.exists()) && !dst.mkdirs()) {
                return false;
            }
            boolean success = true;
            File[] childs = src.listFiles();
            if (childs != null) {
                for (File f: childs) {
                    success &= moveFileOrDir(f, new File(dst, f.getName()));
                }
            }
            return success && src.delete();
        } else {
            try (InputStream input = new BufferedInputStream(new FileInputStream(src));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(dst))) {
                com.phlox.server.utils.Utils.copyStream(input, output);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            return src.delete();
        }
    }

    public static String readUntil(InputStream in, String delimiter, Charset charset) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] delimBytes = delimiter.getBytes(charset);
        int matchPos = 0;

        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            if (b == delimBytes[matchPos]) {
                matchPos++;
                if (matchPos == delimBytes.length) {
                    break;
                }
            } else {
                matchPos = (b == delimBytes[0]) ? 1 : 0;
            }
        }

        byte[] full = buffer.toByteArray();
        int resultLen = full.length - ((matchPos == delimBytes.length) ? delimBytes.length : 0);
        return new String(full, 0, resultLen, charset);
    }

    public static boolean isAndroid() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
