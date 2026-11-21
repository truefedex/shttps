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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public final class Utils {
    private static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(Utils.class);

    private Utils() {}

    public static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer);
            if (count == -1) {
                break;
            }
            output.write(buffer, 0, count);
        }
    }

    public static void copyStream(InputStream input, OutputStream output, long amount) throws IOException {
        byte[] buffer = new byte[8192];
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

    /**
     * Copies a file or a directory tree. Links and special files (see {@link #isLinkOrSpecial})
     * are left out, wherever they sit in the tree: the server neither lists nor serves them, so a
     * copy must not follow one out of the share either. A destination that is itself a link is
     * refused instead of written through.
     */
    public static boolean copyFileOrDir(File src, File dst) {
        if (isLinkOrSpecial(src)) {
            return true;
        }
        if (isLinkOrSpecial(dst)) {
            return false;
        }
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
                logger.e("Can not copy " + src + " to " + dst, e);
                return false;
            }
            return true;
        }
    }

    public static boolean moveFileOrDir(File src, File dst) {
        //a rename moves a link as the link, which is harmless
        if (src.renameTo(dst)) {
            return true;
        }
        //the fallback copies and deletes. It must neither walk through a link (isDirectory() and
        //FileInputStream follow it) nor write through one at the destination
        if (isLinkOrSpecial(src) || isLinkOrSpecial(dst)) {
            return false;
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
                logger.e("Can not move " + src + " to " + dst, e);
                return false;
            }
            return src.delete();
        }
    }

    /**
     * Whether {@code file} is something the server never follows: a symbolic link, a Windows
     * junction or mount point, or a special file (FIFO, socket, device). The check looks at the
     * entry itself, not at what it points to.
     * <p>
     * The policy is deliberately blunt. Such entries are not listed, not resolved by name and not
     * walked, whether their target is inside the share or not: checking where a link leads is
     * the fragile part (case, 8.3 names, races, junctions that {@link Files#isSymbolicLink} does
     * not report), refusing all of them is not.
     * <p>
     * On Windows every reparse point reports as "other", and that includes OneDrive placeholders
     * that redirect nowhere. Those are let through when their real path is exactly where they
     * appear to be. A missing file is not a link; an entry whose attributes cannot be read is
     * treated as one.
     */
    public static boolean isLinkOrSpecial(File file) {
        if (file == null) return false;
        Path path;
        BasicFileAttributes attrs;
        try {
            path = file.toPath();
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
        if (attrs.isSymbolicLink()) return true;
        if (!attrs.isOther()) return false;
        if (File.separatorChar != '\\') return true;
        try {
            Path real = path.toRealPath();
            Path parent = path.toAbsolutePath().getParent();
            if (parent == null) return true;
            Path expected = parent.toRealPath().resolve(path.getFileName());
            return !real.toString().equalsIgnoreCase(expected.toString());
        } catch (IOException | RuntimeException e) {
            return true;
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
