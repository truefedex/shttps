package com.phlox.simpleserver.test;

import com.phlox.server.SHTTPSConfigImpl;
import com.phlox.server.utils.docfile.RawDocumentFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RootDirPathEncodingTest {

    @Test
    void loadsLegacyPercentEncodedRootDir(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{\"root_dir\":\"C:\\\\Program%20Files\\\\WindowsApps\\\\app\\\\www\"}",
                StandardCharsets.UTF_8);

        SHTTPSConfigImpl config = new SHTTPSConfigImpl(configFile.toFile());
        RawDocumentFile root = (RawDocumentFile) config.getRootDir();
        assertNotNull(root);
        String path = root.getFile().getAbsolutePath();
        assertFalse(path.contains("%20"), path);
        assertTrue(path.contains("Program Files"), path);
    }

    @Test
    void storesDecodedPathWhenGivenEncodedUri(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("config.json");
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(configFile.toFile());
        config.setRootDir("file:/C:/Program%20Files/WindowsApps/app/www");

        RawDocumentFile root = (RawDocumentFile) config.getRootDir();
        String path = root.getFile().getAbsolutePath();
        assertFalse(path.contains("%20"), path);
        assertTrue(path.contains("Program Files"), path);
    }
}
