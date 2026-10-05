package com.phlox.server.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class UtilsTest {
    @TempDir
    File tempDir;

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void copyStreamCopiesEverything() throws IOException {
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utils.copyStream(new ByteArrayInputStream(data), out);
        assertArrayEquals(data, out.toByteArray());
        assertArrayEquals(data, Utils.readAllBytes(new ByteArrayInputStream(data)));
    }

    @Test
    public void copyStreamWithAmountStopsThere() throws IOException {
        ByteArrayInputStream input = in("0123456789");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utils.copyStream(input, out, 4);
        assertEquals("0123", out.toString("UTF-8"));
        assertEquals('4', input.read());

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        Utils.copyStream(in("abc"), all, 100);
        assertEquals("abc", all.toString("UTF-8"));
    }

    @Test
    public void readUntilDelimiter() throws IOException {
        ByteArrayInputStream input = in("key: value\r\n\r\nrest");
        assertEquals("key: value", Utils.readUntil(input, "\r\n", StandardCharsets.UTF_8));
        assertEquals("", Utils.readUntil(input, "\r\n", StandardCharsets.UTF_8));
        assertEquals("rest", Utils.readUntil(input, "\r\n", StandardCharsets.UTF_8));
        //a partial match followed by the real one
        assertEquals("a-b", Utils.readUntil(in("a-b--c"), "--", StandardCharsets.UTF_8));
    }

    private File tree(String name) throws IOException {
        File dir = new File(tempDir, name);
        assertTrue(new File(dir, "sub").mkdirs());
        Files.write(new File(dir, "a.txt").toPath(), "A".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "sub/b.txt").toPath(), "B".getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void copyFileOrDirCopiesRecursively() throws IOException {
        File src = tree("src");
        File dst = new File(tempDir, "copy");
        assertTrue(Utils.copyFileOrDir(src, dst));
        assertEquals("A", read(new File(dst, "a.txt")));
        assertEquals("B", read(new File(dst, "sub/b.txt")));
        assertTrue(new File(src, "sub/b.txt").exists());
    }

    @Test
    public void moveFileOrDirMoves() throws IOException {
        File src = tree("src");
        File dst = new File(tempDir, "moved");
        assertTrue(Utils.moveFileOrDir(src, dst));
        assertEquals("B", read(new File(dst, "sub/b.txt")));
        assertFalse(src.exists());
    }

    @Test
    public void copyOfAMissingFileFails() {
        assertFalse(Utils.copyFileOrDir(new File(tempDir, "missing.txt"), new File(tempDir, "out.txt")));
    }

    @Test
    public void notAndroidHere() {
        assertFalse(Utils.isAndroid());
    }
}
