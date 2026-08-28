package com.phlox.simpleserver.test;

import com.phlox.server.SHTTPSConfigImpl;
import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A config that enables the database without naming a file - the normal state of a hand written
 * command line config, since {@code database_path} has no default and the desktop build writes it
 * out as an empty string. It used to reach {@code new File((String) null)} and take the database
 * down with an NPE that was logged as "Failed to open database" and nothing else.
 */
public class DatabaseDefaultPathTest {
    private SHTTPSApp app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            SHTTPSApp.destroy();
            app = null;
        }
    }

    @Test
    void opensDefaultDatabaseWhenPathIsMissing(@TempDir Path tempDir) throws Exception {
        SHTTPSConfigImpl config = configWithDatabaseEnabled(tempDir);
        assertNull(config.getDatabasePath(), "precondition: no path in the config");

        assertDatabaseOpenedNextToConfig(config, tempDir);
    }

    /**
     * The same, for the empty string {@code saveDefaultValues()} writes into a fresh config file -
     * a null-only check here would silently hand SQLite an empty file name and get an in-memory
     * database that disappears on the next restart.
     */
    @Test
    void opensDefaultDatabaseWhenPathIsEmpty(@TempDir Path tempDir) throws Exception {
        SHTTPSConfigImpl config = configWithDatabaseEnabled(tempDir);
        config.setDatabasePath("");
        assertEquals("", config.getDatabasePath(), "precondition: empty path in the config");

        assertDatabaseOpenedNextToConfig(config, tempDir);
    }

    @Test
    void keepsExplicitPath(@TempDir Path tempDir) throws Exception {
        SHTTPSConfigImpl config = configWithDatabaseEnabled(tempDir);
        Path explicit = tempDir.resolve("chosen.db");
        config.setDatabasePath(explicit.toAbsolutePath().toString());

        app = SHTTPSApp.init(config, new PlatformUtilsImpl(), new DatabaseFabricImpl());
        app.initIO();

        assertNotNull(app.getDatabase());
        assertEquals(explicit.toAbsolutePath().toString(), app.getDatabase().getPath());
        assertTrue(Files.exists(explicit));
        assertFalse(Files.exists(tempDir.resolve(SHTTPSConfigImpl.DEFAULT_DATABASE_FILE_NAME)));
    }

    /**
     * A bare {@code --config config.json} has no parent directory, which is the very null this
     * change is about. Writes into the working directory because the constructor saves the file,
     * so it cleans up after itself.
     */
    @Test
    void defaultPathSurvivesConfigFileWithoutParent() throws Exception {
        File configFile = new File("shttps-parentless-config-test.json");
        try {
            SHTTPSConfigImpl config = new SHTTPSConfigImpl(configFile);
            String defaultPath = config.getDefaultDatabasePath();

            assertNotNull(defaultPath);
            File expected = new File(configFile.getAbsoluteFile().getParentFile(),
                    SHTTPSConfigImpl.DEFAULT_DATABASE_FILE_NAME);
            assertEquals(expected.getAbsolutePath(), defaultPath);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            configFile.delete();
        }
    }

    private SHTTPSConfigImpl configWithDatabaseEnabled(Path tempDir) throws Exception {
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(tempDir.resolve("config.json").toFile());
        //set before SHTTPSApp.init(), or it would fall back to the real ~/.shttps/www
        config.setRootDir(tempDir.toAbsolutePath().toString());
        config.setDatabaseEnabled(true);
        config.setDatabasePath(null);
        return config;
    }

    private void assertDatabaseOpenedNextToConfig(SHTTPSConfigImpl config, Path tempDir) {
        app = SHTTPSApp.init(config, new PlatformUtilsImpl(), new DatabaseFabricImpl());
        app.initIO();

        Path expected = tempDir.resolve(SHTTPSConfigImpl.DEFAULT_DATABASE_FILE_NAME);
        assertNotNull(app.getDatabase(), "database should have been opened at the default path");
        assertEquals(expected.toAbsolutePath().toString(), app.getDatabase().getPath());
        assertTrue(Files.exists(expected), "default database file should exist: " + expected);
        //resolved once and written back, so the status page and backup export see the same file
        assertEquals(expected.toAbsolutePath().toString(), config.getDatabasePath());
    }
}
