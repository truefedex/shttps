package com.phlox.server.utils.docfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class RawDocumentFileTest {
    @TempDir
    File tempDir;

    private RawDocumentFile root;

    @BeforeEach
    public void setUp() throws IOException {
        assertTrue(new File(tempDir, "dir/sub").mkdirs());
        write(new File(tempDir, "dir/a.txt"), "hello");
        write(new File(tempDir, "dir/sub/b.bin"), "1234567890");
        root = (RawDocumentFile) DocumentFile.fromFile(tempDir);
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void attributesOfFilesAndDirectories() {
        DocumentFile dir = root.findFile("dir");
        DocumentFile file = dir.findFile("a.txt");
        assertTrue(dir.isDirectory());
        assertFalse(dir.isFile());
        assertNull(dir.getType());
        assertTrue(file.isFile());
        assertFalse(file.isDirectory());
        assertEquals(5, file.length());
        assertEquals("text/plain", file.getType());
        assertEquals("application/octet-stream", dir.findFile("sub").findFile("b.bin").getType());
        assertTrue(file.exists());
        assertTrue(file.canRead());
        assertTrue(file.lastModified() > 0);
        assertTrue(file.created() > 0);
        assertFalse(file.isVirtual());
        assertEquals("a.txt", file.getName());
        assertEquals(dir, file.getParentFile());
        assertTrue(file.getUri().startsWith("file:/"));
        assertTrue(file.getStorageSize() > 0);
        assertTrue(file.getStorageFreeSpace() >= 0);
    }

    @Test
    public void missingFileHasFileLikeDefaults() {
        RawDocumentFile missing = new RawDocumentFile(root, new File(tempDir, "missing"));
        assertFalse(missing.exists());
        assertEquals(0, missing.length());
        assertEquals(0, missing.lastModified());
        assertNull(root.findFile("missing"));
    }

    @Test
    public void attributesAreCachedUntilChangedThroughThisInstance() throws IOException {
        DocumentFile file = root.findFile("dir").findFile("a.txt");
        assertEquals(5, file.length());
        //an outside change is not observed by an instance that already read its attributes
        write(new File(tempDir, "dir/a.txt"), "longer content");
        assertEquals(5, file.length());
        //writing through the instance resets the cache
        try (OutputStream out = file.openOutputStream()) {
            out.write("xy".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(2, file.length());
    }

    @Test
    public void symlinkedDirectoryIsNotADirectory() throws IOException {
        File link = new File(tempDir, "link");
        try {
            Files.createSymbolicLink(link.toPath(), new File(tempDir, "dir").toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
        //listing never descends into a link (a loop, or a way out of the root)
        assertFalse(root.findFile("link").isDirectory());
    }

    @Test
    public void listFiles() {
        Set<String> names = new HashSet<>();
        for (DocumentFile child : root.findFile("dir").listFiles()) {
            names.add(child.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("a.txt", "sub")), names);
        assertEquals(0, new RawDocumentFile(root, new File(tempDir, "missing")).listFiles().length);
    }

    @Test
    public void listFilesFallbackIsAskedWhenListingFails() {
        RawDocumentFile.ListFilesFallback previous = RawDocumentFile.listFilesFallback;
        try {
            File stand_in = new File(tempDir, "dir/a.txt");
            RawDocumentFile.listFilesFallback = dir -> new File[]{stand_in};
            DocumentFile[] files = new RawDocumentFile(root, new File(tempDir, "missing")).listFiles();
            assertEquals(1, files.length);
            assertEquals("a.txt", files[0].getName());
        } finally {
            RawDocumentFile.listFilesFallback = previous;
        }
    }

    @Test
    public void createFileAndDirectory() throws IOException {
        DocumentFile dir = root.findFile("dir");
        DocumentFile created = dir.createFile("text/plain", "new.txt");
        assertNotNull(created);
        assertTrue(created.isFile());
        assertThrows(RuntimeException.class, () -> dir.createFile("text/plain", "new.txt"));

        DocumentFile newDir = dir.createDirectory("newdir");
        assertNotNull(newDir);
        assertTrue(newDir.isDirectory());
        assertNull(dir.createDirectory("newdir"), "an existing directory is not created again");
    }

    @Test
    public void renameAndDelete() {
        DocumentFile dir = root.findFile("dir");
        DocumentFile file = dir.findFile("a.txt");
        assertTrue(file.renameTo("renamed.txt"));
        assertEquals("renamed.txt", file.getName());
        assertTrue(new File(tempDir, "dir/renamed.txt").exists());

        DocumentFile sub = dir.findFile("sub");
        assertTrue(sub.delete());
        assertFalse(new File(tempDir, "dir/sub").exists());
        assertFalse(sub.exists());
    }

    @Test
    public void copyAndMove() throws IOException {
        DocumentFile dir = root.findFile("dir");
        DocumentFile target = root.createDirectory("target");
        assertTrue(dir.findFile("sub").copyTo(target));
        assertEquals("1234567890", read(target.findFile("sub").findFile("b.bin").openInputStream()));
        assertTrue(dir.findFile("a.txt").copyTo(target, "copy.txt"));
        assertEquals("hello", read(target.findFile("copy.txt").openInputStream()));

        assertTrue(dir.findFile("a.txt").moveTo(target));
        assertFalse(new File(tempDir, "dir/a.txt").exists());
        assertTrue(dir.findFile("sub").moveTo(target, "moved-sub"));
        assertTrue(new File(tempDir, "target/moved-sub/b.bin").exists());
    }

    @Test
    public void openInputStreamAtAnOffset() throws IOException {
        DocumentFile file = root.findFile("dir").findFile("sub").findFile("b.bin");
        assertEquals("67890", read(file.openInputStream(5)));
        //FileInputStream.skip goes past the end without complaint, so this is not the
        //"Couldn't seek" error: the stream is simply empty
        assertEquals("", read(file.openInputStream(100)));
    }

    @Test
    public void directorySize() {
        assertEquals(15, root.findFile("dir").calculateDirectorySize());
    }

    @Test
    public void relativePathIsDecoded() throws IOException {
        File nested = new File(tempDir, "dir/with space+plus/ünï");
        assertTrue(nested.mkdirs());
        DocumentFile child = new RawDocumentFile(root, nested);
        assertEquals("dir/with space+plus/ünï/", root.getRelativePath(child));
        assertNull(root.findFile("dir").findFile("a.txt").getRelativePath(child), "a file has no children");
        assertNull(root.getRelativePath(null));
        assertNull(root.findFile("dir").getRelativePath(new RawDocumentFile(null, new File(tempDir, "elsewhere"))));
    }

    @Test
    public void equalityIsByUriAndKind() {
        DocumentFile one = root.findFile("dir");
        DocumentFile two = DocumentFile.fromFile(new File(tempDir, "dir"));
        assertEquals(one, two);
        assertNotEquals(one, root.findFile("dir").findFile("a.txt"));
        assertNotEquals(one, "dir");
    }

    @Test
    public void getFileOnlyForRawDocuments() {
        assertEquals(tempDir, RawDocumentFile.getFile(root));
        assertNull(RawDocumentFile.getFile(null));
    }

    @Test
    public void bytesWrittenCanBeReadBack() throws IOException {
        DocumentFile file = root.createFile("application/octet-stream", "data.bin");
        byte[] data = {0, 1, 2, (byte) 0xff};
        try (OutputStream out = file.openOutputStream()) {
            out.write(data);
        }
        try (InputStream in = file.openInputStream()) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }
}
