package com.phlox.server.utils.docfile;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class RawDocumentFileToFilePathTest {

    @Test
    void fileUriWithEncodedSpaceDecodes() {
        String path = RawDocumentFile.fileUriToFilePath(
                "file:/C:/Program%20Files/WindowsApps/app/www");
        assertFalse(path.contains("%20"));
        assertTrue(path.contains("Program Files"));
    }

    @Test
    void fileUriTripleSlashWithEncodedSpaceDecodes() {
        String path = RawDocumentFile.fileUriToFilePath(
                "file:///C:/Program%20Files/WindowsApps/app/www");
        assertFalse(path.contains("%20"));
        assertTrue(path.contains("Program Files"));
    }

    @Test
    void encodedFilesystemPathDecodes() {
        String path = RawDocumentFile.toFilePath(
                "C:\\Program%20Files\\WindowsApps\\app\\www");
        assertFalse(path.contains("%20"));
        assertTrue(path.contains("Program Files"));
    }

    @Test
    void plainPathWithSpacesIsUnchanged() {
        String original = "C:\\Program Files\\WindowsApps\\app\\www";
        assertEquals(original, RawDocumentFile.toFilePath(original));
    }

    @Test
    void unixPathWithEncodedSpaceDecodes() {
        String path = RawDocumentFile.toFilePath("/home/user/My%20Docs/www");
        assertFalse(path.contains("%20"));
        assertTrue(path.contains("My Docs"));
        assertTrue(path.replace('\\', '/').contains("/home/user/My Docs/www")
                || new File(path).getAbsolutePath().contains("My Docs"));
    }
}
