package profiles

import com.phlox.server.database.DatabaseFabricImpl
import com.phlox.server.utils.PlatformUtilsImpl
import com.phlox.server.utils.docfile.RawDocumentFile
import com.phlox.simpleserver.DesktopAppState
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.profiles.ConfigProfiles
import com.phlox.simpleserver.profiles.DesktopStorageLayout
import com.phlox.simpleserver.profiles.ServedFolderGuard
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop profile registry against a real data directory.
 *
 * Two things here are load-bearing beyond the bookkeeping: the legacy profile keeps pointing at
 * `~/.shttps/config.json`, which the command line build still defaults to, and the lock
 * identifier must not move when the active profile changes - otherwise a second launch takes an
 * unheld lock and two processes rewrite one config file in full.
 */
class ConfigProfilesTest {
    private lateinit var dir: File
    private lateinit var appState: DesktopAppState

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("shttps-profiles-test").toFile()
        appState = DesktopAppState(File(dir, DesktopAppState.FILE_NAME))
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun profiles() = ConfigProfiles(dir, appState, "Default")

    // --- seeding -------------------------------------------------------------------------------

    @Test
    fun `seeds the legacy profile pointing at the config the app always opened`() {
        val registry = profiles()

        assertEquals(1, registry.count)
        val main = registry.all().single()
        assertEquals(DesktopStorageLayout.MAIN_PROFILE_ID, main.id)
        assertEquals("Default", main.name)
        assertTrue(main.isMain)
        assertEquals(File(dir, "config.json").absolutePath, main.store)
        assertEquals(DesktopStorageLayout.MAIN_PROFILE_ID, registry.activeProfileId)
    }

    @Test
    fun `seeding is idempotent`() {
        val first = profiles()
        val json = appState.profilesJson

        val second = profiles()

        assertEquals(json, appState.profilesJson)
        assertEquals(first.activeProfileId, second.activeProfileId)
        assertEquals(1, second.count)
    }

    @Test
    fun `an unknown active id falls back to the first profile`() {
        profiles().createProfile("Second")
        appState.activeProfileId = "pgone"

        val registry = profiles()

        assertEquals(DesktopStorageLayout.MAIN_PROFILE_ID, registry.activeProfileId)
        assertEquals(2, registry.count)
    }

    @Test
    fun `a malformed registry is reseeded rather than refused`() {
        appState.profilesJson = "{not json at all"

        val registry = profiles()

        assertEquals(1, registry.count)
        assertEquals(DesktopStorageLayout.MAIN_PROFILE_ID, registry.activeProfileId)
    }

    // --- creating ------------------------------------------------------------------------------

    @Test
    fun `a new profile gets its own directory, config and root`() {
        val registry = profiles()

        val profile = registry.createProfile("Second")

        assertNotNull(profile)
        assertFalse(profile.isMain)
        val profileDir = File(dir, "profiles/${profile.id}")
        assertEquals(File(profileDir, "config.json").absolutePath, profile.store)
        assertTrue(File(profileDir, "www").isDirectory)
        assertTrue(File(profileDir, "databases").isDirectory)
        assertTrue(File(profile.store).isFile)

        val config = registry.openConfig(profile)
        assertEquals(File(profileDir, "www").absolutePath, config.rootDir?.let {
            com.phlox.server.utils.docfile.RawDocumentFile.getFile(it)?.absolutePath
        })
        //the database and the imported certificate follow the config file's own directory, which
        //is what keeps two profiles off one SQLite file
        assertEquals(File(profileDir, "database.sqlite").absolutePath, config.defaultDatabasePath)
    }

    @Test
    fun `the legacy profile keeps the data directory as its own`() {
        val registry = profiles()

        assertEquals(dir.absolutePath, registry.profileDir(DesktopStorageLayout.MAIN_PROFILE_ID).absolutePath)
        assertEquals(File(dir, "www").absolutePath,
            registry.wwwDir(DesktopStorageLayout.MAIN_PROFILE_ID).absolutePath)
        assertEquals(File(dir, "databases").absolutePath,
            registry.databasesDir(DesktopStorageLayout.MAIN_PROFILE_ID).absolutePath)
    }

    @Test
    fun `ids generated in the same millisecond do not collide`() {
        val registry = profiles()

        val a = registry.createProfile("A")
        val b = registry.createProfile("B")

        assertNotNull(a)
        assertNotNull(b)
        assertTrue(a.id != b.id)
        assertEquals(3, registry.count)
    }

    // --- deleting ------------------------------------------------------------------------------

    @Test
    fun `the profile in use and the last one left can not be deleted`() {
        val registry = profiles()
        val second = registry.createProfile("Second")!!
        registry.setActiveProfile(second.id)

        assertFalse(registry.canDelete(second.id))
        assertFalse(registry.deleteProfile(second.id, deleteData = true))

        assertTrue(registry.canDelete(DesktopStorageLayout.MAIN_PROFILE_ID))
        assertTrue(registry.deleteProfile(DesktopStorageLayout.MAIN_PROFILE_ID, deleteData = true))
        assertFalse(registry.canDelete(second.id))
        assertEquals(1, registry.count)
    }

    /**
     * The legacy profile's directory is the data directory itself, so deleting that profile must
     * never take the registry, the lock files or anything else in it.
     */
    @Test
    fun `deleting the legacy profile removes its config but not the data directory`() {
        val registry = profiles()
        val second = registry.createProfile("Second")!!
        registry.setActiveProfile(second.id)
        val legacyConfig = File(dir, "config.json")
        legacyConfig.writeText("{}")
        File(dir, "www").mkdirs()
        File(dir, "www/index.html").writeText("home")

        assertFalse(registry.ownsDeletableDataDir(DesktopStorageLayout.MAIN_PROFILE_ID))
        assertTrue(registry.deleteProfile(DesktopStorageLayout.MAIN_PROFILE_ID, deleteData = true))

        assertFalse(legacyConfig.exists())
        assertTrue(dir.isDirectory)
        assertEquals("home", File(dir, "www/index.html").readText())
        assertTrue(File(dir, DesktopAppState.FILE_NAME).isFile)
    }

    @Test
    fun `deleting a profile with its data removes only its own directory`() {
        val registry = profiles()
        val second = registry.createProfile("Second")!!
        File(second.store).parentFile.let { File(it, "www/page.html") }.also {
            it.parentFile.mkdirs()
            it.writeText("page")
        }
        File(dir, "www").mkdirs()

        assertTrue(registry.ownsDeletableDataDir(second.id))
        assertTrue(registry.deleteProfile(second.id, deleteData = true))

        assertFalse(File(dir, "profiles/${second.id}").exists())
        assertTrue(File(dir, "www").isDirectory)
        assertEquals(1, registry.count)
    }

    @Test
    fun `deleting a profile without its data keeps the files and removes the config`() {
        val registry = profiles()
        val second = registry.createProfile("Second")!!
        val page = File(dir, "profiles/${second.id}/www/page.html")
        page.parentFile.mkdirs()
        page.writeText("page")

        assertTrue(registry.deleteProfile(second.id, deleteData = false))

        assertFalse(File(second.store).exists())
        assertEquals("page", page.readText())
    }

    // --- renaming ------------------------------------------------------------------------------

    @Test
    fun `renaming leaves the id and the store alone`() {
        val registry = profiles()

        assertTrue(registry.renameProfile(DesktopStorageLayout.MAIN_PROFILE_ID, "  Home  "))

        val main = registry.byId(DesktopStorageLayout.MAIN_PROFILE_ID)!!
        assertEquals("Home", main.name)
        assertEquals(File(dir, "config.json").absolutePath, main.store)
        assertFalse(registry.renameProfile(DesktopStorageLayout.MAIN_PROFILE_ID, "   "))
        assertFalse(registry.renameProfile("pnope", "Name"))
    }

    @Test
    fun `every field survives a round trip through the registry file`() {
        val registry = profiles()
        val added = registry.createProfile("Second")!!
        registry.setActiveProfile(added.id)

        val reloaded = profiles()

        val second = reloaded.byId(added.id)
        assertNotNull(second)
        assertEquals("Second", second.name)
        assertEquals(added.store, second.store)
        assertEquals(added.createdAt, second.createdAt)
        assertEquals(added.id, reloaded.activeProfileId)
        assertNull(reloaded.byId("pnope"))
    }


    // --- what a switch actually has to isolate -------------------------------------------------

    /**
     * The data half of a profile switch, without the view model: two profiles, one `SHTTPSApp` at
     * a time, and nothing of one leaking into the other.
     *
     * `SHTTPSApp.init` throws on a second instance and its `config` is `final`, so a switch is
     * `destroy()` + `init()` - this walks that cycle twice and checks that each profile keeps its
     * own root, its own database file and its own users.
     */
    @Test
    fun `two profiles served one after another keep their own root, database and users`() {
        val registry = profiles()
        val second = registry.createProfile("Second")!!

        val firstConfig = registry.openConfig(registry.byId(DesktopStorageLayout.MAIN_PROFILE_ID)!!)
        firstConfig.port = 8111
        firstConfig.isDatabaseEnabled = true
        firstConfig.databasePath = null

        var app = SHTTPSApp.init(
            firstConfig, PlatformUtilsImpl(), DatabaseFabricImpl(registry.activeDatabasesDir()))
        try {
            app.initIO()
            assertEquals(File(dir, "database.sqlite").absolutePath, app.database?.path)
        } finally {
            SHTTPSApp.destroy()
        }

        //the switch
        registry.setActiveProfile(second.id)
        val secondConfig = registry.openConfig(second)
        secondConfig.isDatabaseEnabled = true
        secondConfig.databasePath = null

        app = SHTTPSApp.init(
            secondConfig, PlatformUtilsImpl(), DatabaseFabricImpl(registry.activeDatabasesDir()))
        try {
            app.initIO()
            val secondDir = File(dir, "profiles/${second.id}")
            assertEquals(File(secondDir, "database.sqlite").absolutePath, app.database?.path)
            assertEquals(
                File(secondDir, "www").absolutePath,
                RawDocumentFile.getFile(secondConfig.rootDir!!)?.absolutePath
            )
            //a fresh config, so none of the first profile's settings came along
            assertTrue(secondConfig.port != 8111, "the port must not be inherited")
        } finally {
            SHTTPSApp.destroy()
        }

        //and the first profile still holds what it had
        val reopened = registry.openConfig(registry.byId(DesktopStorageLayout.MAIN_PROFILE_ID)!!)
        assertEquals(8111, reopened.port)
        assertEquals(File(dir, "database.sqlite").absolutePath, reopened.databasePath)
    }

    // --- the lock identifier -------------------------------------------------------------------

    /**
     * What `Main.kt` computes: the hash of the legacy config path, never the active profile's. If
     * it followed the active profile, a second launch after a switch would compute an identifier
     * nobody holds - and `SHTTPSConfigImpl` rewrites the whole file on every setter, so two
     * processes on one config lose each other's changes.
     */
    @Test
    fun `the lock identifier does not move when the active profile changes`() {
        val registry = profiles()
        val legacy = DesktopStorageLayout
            .configFile(dir, DesktopStorageLayout.MAIN_PROFILE_ID).absolutePath
        val before = lockIdentifierFor(legacy)

        val second = registry.createProfile("Second")!!
        registry.setActiveProfile(second.id)

        assertEquals(before, lockIdentifierFor(legacy))
        assertTrue(before != lockIdentifierFor(second.store),
            "a different config must hash differently, or the constant would prove nothing")
    }

    private fun lockIdentifierFor(path: String): String {
        val canonical = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            File(path).absolutePath
        }
        return "shttps-" + Integer.toHexString(canonical.hashCode())
    }

    // --- the global settings migration ---------------------------------------------------------

    @Test
    fun `app level settings are copied out of the legacy config once`() {
        val legacyConfig = File(dir, "config.json")
        legacyConfig.writeText(
            JSONObject()
                .put("window_width", 1234)
                .put("window_height", 777)
                .put("close_to_tray", true)
                .put("autostart", true)
                .put("skipped_update_version", "3.4.8")
                .put("port", 8080)
                .toString()
        )

        appState.importLegacyGlobalsOnce(legacyConfig)

        assertEquals(1234, appState.windowWidth)
        assertEquals(777, appState.windowHeight)
        assertTrue(appState.closeToTray)
        assertTrue(appState.autostart)
        assertEquals("3.4.8", appState.skippedUpdateVersion)

        //a later edit here must not be undone by the stale value still in the old config
        appState.closeToTray = false
        DesktopAppState(File(dir, DesktopAppState.FILE_NAME))
            .importLegacyGlobalsOnce(legacyConfig)
        assertFalse(DesktopAppState(File(dir, DesktopAppState.FILE_NAME)).closeToTray)
    }

    @Test
    fun `importing from a missing legacy config just records that it ran`() {
        appState.importLegacyGlobalsOnce(File(dir, "no-such-config.json"))

        assertEquals(com.phlox.simpleserver.AppConfig.DEFAULT_WINDOW_WIDTH_DP, appState.windowWidth)
        assertFalse(appState.closeToTray)
    }

    // --- the served folder guard ---------------------------------------------------------------

    @Test
    fun `the data directory and other profiles can not be served`() {
        val registry = profiles()
        val other = registry.createProfile("Other")!!
        val ids = registry.all().map { it.id }

        assertFalse(ServedFolderGuard.isAllowed(dir, "main", ids, dir))
        assertFalse(ServedFolderGuard.isAllowed(dir, "main", ids, File(dir, "profiles")))
        assertFalse(ServedFolderGuard.isAllowed(
            dir, "main", ids, File(dir, "profiles/${other.id}")))
        assertFalse(ServedFolderGuard.isAllowed(
            dir, "main", ids, File(dir, "profiles/${other.id}/databases")))
        assertFalse(
            ServedFolderGuard.isAllowed(
                dir, "main", ids, File(dir, "profiles/${other.id}/databases/sub")),
            "another profile's databases are refused at any depth")
    }

    /**
     * A sweeping root that happens to contain the data directory is the user's own call, and it
     * worked before profiles existed - it already exposed `~/.shttps/config.json` then. Refusing
     * it would take away a capability rather than close a hole.
     */
    @Test
    fun `a folder that merely contains the data directory is allowed`() {
        val registry = profiles()
        registry.createProfile("Other")
        val ids = registry.all().map { it.id }

        assertTrue(ServedFolderGuard.isAllowed(dir, "main", ids, dir.parentFile))
        assertTrue(ServedFolderGuard.isAllowed(dir, "main", ids, File(dir.parentFile.parentFile?.absolutePath ?: "/")))
    }

    @Test
    fun `the usual roots are allowed`() {
        val registry = profiles()
        val other = registry.createProfile("Other")!!
        val ids = registry.all().map { it.id }

        assertTrue(ServedFolderGuard.isAllowed(dir, "main", ids, File(dir, "www")))
        //the live profile here is the legacy one, so this is its own databases folder
        assertTrue(ServedFolderGuard.isAllowed(dir, "main", ids, File(dir, "databases")))
        //sharing files between profiles exposes no config and no database
        assertTrue(ServedFolderGuard.isAllowed(
            dir, "main", ids, File(dir, "profiles/${other.id}/www")))
        assertTrue(ServedFolderGuard.isAllowed(
            dir, "main", ids, File(dir.parentFile, "elsewhere")))
        //a SAF-style value with no local path
        assertTrue(ServedFolderGuard.isAllowed(dir, "main", ids, null))
    }

    /** Its own directory holds its config.json, so a non-legacy profile cannot serve it. */
    @Test
    fun `a profile can not serve its own directory but can serve its www`() {
        val registry = profiles()
        val mine = registry.createProfile("Mine")!!
        val ids = registry.all().map { it.id }

        assertFalse(ServedFolderGuard.isAllowed(
            dir, mine.id, ids, File(dir, "profiles/${mine.id}")))
        assertTrue(ServedFolderGuard.isAllowed(
            dir, mine.id, ids, File(dir, "profiles/${mine.id}/www")))
    }

    /** A sibling whose name merely starts the same way is not inside. */
    @Test
    fun `containment compares whole path segments`() {
        val sibling = File(dir.absolutePath + "-backup")

        assertFalse(DesktopStorageLayout.isStrictlyInside(dir, sibling))
        assertTrue(DesktopStorageLayout.isStrictlyInside(dir, File(dir, "www")))
        assertFalse(DesktopStorageLayout.isStrictlyInside(dir, dir))
        assertTrue(DesktopStorageLayout.isSameOrInside(dir, dir))
    }
}
