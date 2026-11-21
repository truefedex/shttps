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
    public void contentUriDetection() {
        assertTrue(DocumentFileUtils.isContentUri("content://com.android.externalstorage/tree/x"));
        assertFalse(DocumentFileUtils.isContentUri("file:/tmp/x"));
        assertFalse(DocumentFileUtils.isContentUri(null));
    }
}
