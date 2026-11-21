package com.phlox.server.utils.docfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DocumentFileUtilsTest {
    @TempDir
    File tempDir;

    private File rootDir;
    private DocumentFile root;

    @BeforeEach
    public void setUp() throws IOException {
        //root sits one level down, so that its parent holds something worth protecting
        rootDir = new File(tempDir, "root");
        assertTrue(new File(rootDir, "users/alice/docs").mkdirs());
        assertTrue(new File(rootDir, "users/alice/docs/a.txt").createNewFile());
        assertTrue(new File(rootDir, "users/bob").mkdirs());
        assertTrue(new File(tempDir, "outside.txt").createNewFile());
        root = DocumentFile.fromFile(rootDir);
    }

    @Test
    public void resolvesNestedPaths() {
        DocumentFile file = DocumentFileUtils.findChildByPath(root, "/users/alice/docs/a.txt");
        assertNotNull(file);
        assertEquals("a.txt", file.getName());
        assertTrue(file.isFile());
    }

    @Test
    public void rootAndEmptyPathResolveToRoot() {
        assertSame(root, DocumentFileUtils.findChildByPath(root, "/"));
        assertSame(root, DocumentFileUtils.findChildByPath(root, ""));
    }

    @Test
    public void emptyAndDotSegmentsAreSkipped() {
        DocumentFile file = DocumentFileUtils.findChildByPath(root, "/users//./alice/");
        assertNotNull(file);
        assertEquals("alice", file.getName());
    }

    @Test
    public void missingPartReturnsNull() {
        assertNull(DocumentFileUtils.findChildByPath(root, "/users/carol/docs"));
    }

    @Test
    public void parentSegmentIsRejectedAnywhere() {
        for (String path : new String[]{"..", "/..", "/users/..", "/users/../users", "../outside.txt",
                "/users/alice/../../.."}) {
            assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, path), path);
        }
    }

    @Test
    public void prefixIsAppliedBeforeValidation() {
        DocumentFile docs = DocumentFileUtils.findChildByPath(root, "/docs", "users/alice");
        assertNotNull(docs);
        assertEquals("docs", docs.getName());
        //a jailed user must not climb out of the folder the prefix points at
        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/..", "users/alice"));
        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/..", "/users/alice/"));
    }

    @Test
    public void prefixWithBackslashesIsNormalized() {
        DocumentFile docs = DocumentFileUtils.findChildByPath(root, "/docs", "users\\alice");
        assertNotNull(docs);
        assertEquals("docs", docs.getName());
    }

    @Test
    public void backslashTraversalIsRejectedOnWindows() {
        assumeTrue(File.separatorChar == '\\');
        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/..\\outside.txt"));
        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/users\\..\\.."));
        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/.. "));
    }

    @Test
    public void aSymlinkInsideTheShareIsNeitherResolvedNorListed() throws IOException {
        File outside = new File(tempDir, "outside.txt");
        Path link = new File(rootDir, "link").toPath();
        assumeSymlink(link, outside.toPath());

        assertThrows(SecurityException.class, () -> DocumentFileUtils.findChildByPath(root, "/link"));
        assertFalse(names(root.listFiles()).contains("link"));
        assertTrue(outside.exists());
    }

    @Test
    public void aSymlinkIsRefusedEvenWhenItsTargetIsInsideTheShare() throws IOException {
        Path link = new File(rootDir, "users/alice/to-docs").toPath();
        assumeSymlink(link, new File(rootDir, "users/alice/docs").toPath());
        assertThrows(SecurityException.class,
                () -> DocumentFileUtils.findChildByPath(root, "/users/alice/to-docs/a.txt"));
    }

    @Test
    public void aDanglingSymlinkDoesNotLookLikeAFreeName() throws IOException {
        Path link = new File(rootDir, "dangling").toPath();
        assumeSymlink(link, new File(tempDir, "does-not-exist").toPath());
        assertThrows(SecurityException.class, () -> root.findFile("dangling"));
    }

    @Test
    public void aSymlinkedRootIsStillServed() throws IOException {
        Path linkedRoot = new File(tempDir, "linked-root").toPath();
        assumeSymlink(linkedRoot, rootDir.toPath());
        DocumentFile viaLink = DocumentFile.fromFile(linkedRoot.toFile());
        assertTrue(names(viaLink.listFiles()).contains("users"));
        assertNotNull(DocumentFileUtils.findChildByPath(viaLink, "/users/alice/docs/a.txt"));
    }

    @Test
    public void aWindowsJunctionIsNeitherResolvedNorListedNorWalkedOnDelete() throws Exception {
        assumeTrue(File.separatorChar == '\\', "junctions exist only on Windows");
        File outsideDir = new File(tempDir, "outside-dir");
        assertTrue(outsideDir.mkdirs());
        File victim = new File(outsideDir, "victim.txt");
        assertTrue(victim.createNewFile());
        assertTrue(new File(rootDir, "sub").mkdirs());
        File junction = new File(rootDir, "sub/j");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                junction.getAbsolutePath(), outsideDir.getAbsolutePath()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0 && junction.exists(), "mklink /J failed");
        assertFalse(Files.isSymbolicLink(junction.toPath()), "a junction is not reported as a symlink");

        DocumentFile sub = root.findFile("sub");
        assertNotNull(sub);
        assertThrows(SecurityException.class, () -> sub.findFile("j"));
        assertFalse(names(sub.listFiles()).contains("j"));
        assertTrue(sub.delete());
        assertTrue(victim.exists());
    }

    @Test
    public void aSymlinkCannotLeaveTheUserRootForAnotherUsersFolder() throws IOException {
        Path link = new File(rootDir, "users/alice/to-bob").toPath();
        assumeSymlink(link, new File(rootDir, "users/bob").toPath());
        assertThrows(SecurityException.class,
                () -> DocumentFileUtils.findChildByPath(root, "/to-bob", "users/alice"));
        assertTrue(new File(rootDir, "users/bob").exists());
    }

    @Test
    public void deletingADirectoryDeletesTheSymlinkAndNotItsTarget() throws IOException {
        File outsideDir = new File(tempDir, "outside-dir");
        assertTrue(outsideDir.mkdirs());
        File victim = new File(outsideDir, "victim.txt");
        assertTrue(victim.createNewFile());
        Path link = new File(rootDir, "sub/out").toPath();
        assertTrue(new File(rootDir, "sub").mkdirs());
        assumeSymlink(link, outsideDir.toPath());

        DocumentFile sub = root.findFile("sub");
        assertNotNull(sub);
        assertTrue(sub.delete());
        assertTrue(victim.exists());
        assertFalse(new File(rootDir, "sub").exists());
    }

    @Test
    public void copyingATreeLeavesItsLinksBehind() throws IOException {
        File src = new File(rootDir, "users/alice/docs");
        assumeSymlink(new File(src, "out").toPath(), new File(tempDir, "outside.txt").toPath());
        File dst = new File(rootDir, "users/bob/docs");
        assertTrue(com.phlox.server.utils.Utils.copyFileOrDir(src, dst));
        assertTrue(new File(dst, "a.txt").isFile());
        assertFalse(Files.exists(new File(dst, "out").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void copyingOntoALinkDoesNotWriteThroughIt() throws IOException {
        File outside = new File(tempDir, "outside.txt");
        Files.writeString(outside.toPath(), "keep");
        File trap = new File(rootDir, "users/bob/a.txt");
        assumeSymlink(trap.toPath(), outside.toPath());
        assertFalse(com.phlox.server.utils.Utils.copyFileOrDir(
                new File(rootDir, "users/alice/docs/a.txt"), trap));
        assertEquals("keep", Files.readString(outside.toPath()));
    }

    private static java.util.List<String> names(DocumentFile[] files) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (DocumentFile file : files) names.add(file.getName());
        return names;
    }

    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks are not available: " + e);
        }
    }

    @Test
    public void contentUriDetection() {
        assertTrue(DocumentFileUtils.isContentUri("content://com.android.externalstorage/tree/x"));
        assertFalse(DocumentFileUtils.isContentUri("file:/tmp/x"));
        assertFalse(DocumentFileUtils.isContentUri(null));
    }
}
