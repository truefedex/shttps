package com.phlox.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

public class MimeTypeMapTest {
    private final MimeTypeMap map = MimeTypeMap.getInstance();

    @Test
    public void isASingleton() {
        assertSame(map, MimeTypeMap.getInstance());
    }

    @Test
    public void commonTypes() {
        assertEquals("text/html", map.getMimeTypeFromExtension("html"));
        assertEquals("application/pdf", map.getMimeTypeFromExtension("pdf"));
        assertEquals("image/png", map.getMimeTypeFromExtension("png"));
        //RFC 9239 made text/javascript the standard type again
        assertEquals("text/javascript", map.getMimeTypeFromExtension("js"));
        assertEquals("font/woff2", map.getMimeTypeFromExtension("woff2"));
    }

    @Test
    public void extensionCaseDoesNotMatter() {
        assertEquals(map.getMimeTypeFromExtension("jpg"), map.getMimeTypeFromExtension("JPG"));
    }

    @Test
    public void unknownExtension() {
        assertNull(map.getMimeTypeFromExtension("definitely-not-a-type"));
        assertNull(map.getMimeTypeFromExtension(null));
    }

    @Test
    public void extensionFromUrl() {
        assertEquals("txt", map.getFileExtensionFromUrl("file:/C:/files/notes.txt"));
        assertEquals("gz", map.getFileExtensionFromUrl("http://host/archive.tar.gz?x=1#top"));
        //a URL encodes '#' and '?' in a file name, so cutting at them is right for its only caller
        assertEquals("txt", map.getFileExtensionFromUrl("file:/C:/files/a%23b.txt"));
        assertNull(map.getFileExtensionFromUrl("file:/C:/files/README"));
        assertNull(map.getFileExtensionFromUrl("http://host/dir/"));
        assertNull(map.getFileExtensionFromUrl(null));
    }
}
