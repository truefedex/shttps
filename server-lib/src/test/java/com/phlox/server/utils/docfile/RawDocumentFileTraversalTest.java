package com.phlox.server.utils.docfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;

/**
 * Names handed to a {@link RawDocumentFile} come straight from requests (a file list to delete,
 * a rename target, an upload name), so none of them may reach outside the directory. These
 * checks pin that down for every method that takes a name.
 */
public class RawDocumentFileTraversalTest {
    @TempDir
    File tempDir;

    private File rootDir;
    private File outside;
    private RawDocumentFile root;

    @BeforeEach
    public void setUp() throws IOException {
        rootDir = new File(tempDir, "root");
        assertTrue(new File(rootDir, "sub").mkdirs());
        assertTrue(new File(rootDir, "sub/file.txt").createNewFile());
        outside = new File(tempDir, "outside.txt");
        assertTrue(outside.createNewFile());
        root = (RawDocumentFile) DocumentFile.fromFile(rootDir);
    }

    @Test
    public void validChildNames() {
        assertTrue(DocumentFile.isValidChildName("file.txt"));
        assertTrue(DocumentFile.isValidChildName(".hidden"));
        assertTrue(DocumentFile.isValidChildName("..double-dot-prefix"));
        assertTrue(DocumentFile.isValidChildName("name with spaces.txt"));
        assertTrue(DocumentFile.isValidChildName("Ünïcødé"));

        assertFalse(DocumentFile.isValidChildName(null));
        assertFalse(DocumentFile.isValidChildName(""));
        assertFalse(DocumentFile.isValidChildName("."));
        assertFalse(DocumentFile.isValidChildName(".."));
        assertFalse(DocumentFile.isValidChildName("../x"));
        assertFalse(DocumentFile.isValidChildName("a/b"));
        assertFalse(DocumentFile.isValidChildName("/abs"));
        assertFalse(DocumentFile.isValidChildName("nul\0byte"));
    }

    @Test
    public void windowsSpecificNamesAreRejectedOnWindows() {
        assumeTrue(File.separatorChar == '\\');
        //Windows strips trailing dots and spaces: these all resolve to the directory itself
        assertFalse(DocumentFile.isValidChildName(".. "));
        assertFalse(DocumentFile.isValidChildName("..."));
        assertFalse(DocumentFile.isValidChildName(" .."));
        assertFalse(DocumentFile.isValidChildName("..\\outside.txt"));
        assertFalse(DocumentFile.isValidChildName("C:"));
        assertFalse(DocumentFile.isValidChildName("file.txt:stream"));
    }

    @Test
    public void backslashIsAPlainCharacterElsewhere() {
        assumeTrue(File.separatorChar == '/');
        assertTrue(DocumentFile.isValidChildName("back\\slash"));
    }

    @Test
    public void findFileDoesNotClimbOut() {
        assertNull(root.findFile(".."));
        assertNull(root.findFile("../outside.txt"));
        assertNull(root.findFile("sub/file.txt"));
        assertNotNull(root.findFile("sub"));
    }

    @Test
    public void findFileKeepsResolvingSelfReferences() {
        //callers that split "/a" on '/' get an empty first segment, it has always meant "here"
        DocumentFile self = root.findFile("");
        assertNotNull(self);
        assertTrue(self.isDirectory());
        assertNotNull(root.findFile("."));
    }

    @Test
    public void deletingParentByNameIsImpossible() {
        //this is what DELETE /api/file/delete {"path":"/","files":[".."]} used to do
        DocumentFile parent = root.findFile("..");
        assertNull(parent);
        assertTrue(outside.exists());
        assertTrue(rootDir.exists());
    }

    @Test
    public void createRejectsTraversalNames() {
        assertNull(root.createFile("text/plain", "../created.txt"));
        assertNull(root.createDirectory("../created"));
        assertNull(root.createDirectory("a/b"));
        assertFalse(new File(tempDir, "created.txt").exists());
        assertFalse(new File(tempDir, "created").exists());
    }

    @Test
    public void renameRejectsTraversalNames() {
        DocumentFile file = root.findFile("sub").findFile("file.txt");
        assertFalse(file.renameTo("../../moved.txt"));
        assertFalse(file.renameTo(".."));
        assertFalse(new File(tempDir, "moved.txt").exists());
        assertTrue(new File(rootDir, "sub/file.txt").exists());

        assertTrue(file.renameTo("renamed.txt"));
        assertEquals("renamed.txt", file.getName());
        assertTrue(new File(rootDir, "sub/renamed.txt").exists());
    }

    @Test
    public void copyAndMoveRejectTraversalNames() {
        DocumentFile file = root.findFile("sub").findFile("file.txt");
        assertFalse(file.copyTo(root, "../copied.txt"));
        assertFalse(file.moveTo(root, "../moved.txt"));
        assertFalse(new File(tempDir, "copied.txt").exists());
        assertFalse(new File(tempDir, "moved.txt").exists());
        assertTrue(new File(rootDir, "sub/file.txt").exists());
    }
}
