package com.phlox.simpleserver.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class CgiMiddlewarePathTest {
    @TempDir
    File tempDir;

    @Test
    public void encodedParentAndBackslashCannotLeaveTheCgiFolder() throws IOException {
        File cgi = new File(tempDir, "cgi");
        assertTrueMkdir(cgi);
        File script = new File(cgi, "hello.py");
        assertTrue(script.createNewFile());
        File outside = new File(tempDir, "secret.py");
        assertTrue(outside.createNewFile());
        List<CgiType> types = List.of(new CgiType("py", CgiType.Mode.SIMPLE, null, null));

        CgiMiddleware.ResolvedScript found = CgiMiddleware.resolveScript(cgi, "/hello.py", types);
        assertEquals(script.getCanonicalFile(), found.script.getCanonicalFile());
        assertEquals("py", found.type.extension);

        //PATH_INFO after the script is not part of the file that gets executed
        assertEquals(script.getCanonicalFile(),
                CgiMiddleware.resolveScript(cgi, "/hello.py/extra/info", types).script.getCanonicalFile());

        assertThrows(SecurityException.class,
                () -> CgiMiddleware.resolveScript(cgi, "/%2e%2e%5csecret.py", types));
        assertThrows(SecurityException.class,
                () -> CgiMiddleware.resolveScript(cgi, "/%2e%2e/%2e%2e/secret.py", types));
        assertThrows(SecurityException.class,
                () -> CgiMiddleware.resolveScript(cgi, "/..\\secret.py", types));
        assertNull(CgiMiddleware.resolveScript(cgi, "/missing.py", types));
    }

    @Test
    public void aSymlinkToAScriptOutsideTheFolderIsRejected() throws IOException {
        File cgi = new File(tempDir, "cgi");
        assertTrueMkdir(cgi);
        File outside = new File(tempDir, "secret.py");
        assertTrue(outside.createNewFile());
        try {
            Files.createSymbolicLink(new File(cgi, "linked.py").toPath(), outside.toPath());
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks are not available: " + e);
            return;
        }
        List<CgiType> types = List.of(new CgiType("py", CgiType.Mode.SIMPLE, null, null));
        assertThrows(SecurityException.class,
                () -> CgiMiddleware.resolveScript(cgi, "/linked.py", types));
        assertTrue(outside.exists());
    }

    private static void assertTrueMkdir(File dir) {
        assertTrue(dir.mkdirs());
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
