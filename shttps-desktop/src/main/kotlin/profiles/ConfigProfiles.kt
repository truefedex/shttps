package com.phlox.simpleserver.profiles

import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.DesktopAppState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One entry of the config profile registry.
 *
 * [store] is the absolute path of this profile's `config.json`, recorded rather than derived. That
 * is what keeps the profile that existed before this feature an ordinary entry - it keeps the
 * `~/.shttps/config.json` the command line build still defaults to - and it leaves room for a
 * profile whose config lives somewhere else entirely.
 *
 * [id] is opaque and permanent; renaming only touches [name], so nothing on disk has to move.
 */
data class ConfigProfile(
    val id: String,
    var name: String,
    val store: String,
    val createdAt: Long,
) {
    val isMain: Boolean get() = DesktopStorageLayout.isMainProfile(id)

    fun serialize(): JSONObject = JSONObject()
        .put(FIELD_ID, id)
        .put(FIELD_NAME, name)
        .put(FIELD_STORE, store)
        .put(FIELD_CREATED_AT, createdAt)

    companion object {
        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
        const val FIELD_STORE = "store"
        const val FIELD_CREATED_AT = "createdAt"

        /** Null for an entry with no usable id or store - a hand-edited or truncated registry. */
        fun deserialize(json: JSONObject): ConfigProfile? {
            val id = json.optString(FIELD_ID, "")
            val store = json.optString(FIELD_STORE, "")
            if (id.isEmpty() || store.isEmpty()) return null
            return ConfigProfile(
                id = id,
                name = json.optString(FIELD_NAME, id),
                store = store,
                createdAt = json.optLong(FIELD_CREATED_AT, 0L),
            )
        }

        /** Drops what it cannot make sense of; the caller seeds a fresh registry from nothing. */
        fun deserializeAll(json: String?): MutableList<ConfigProfile> {
            val result = mutableListOf<ConfigProfile>()
            if (json.isNullOrEmpty()) return result
            val array = try {
                JSONArray(json)
            } catch (e: Exception) {
                return result
            }
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                deserialize(item)?.let { result.add(it) }
            }
            return result
        }

        fun serializeAll(profiles: List<ConfigProfile>): String {
            val array = JSONArray()
            profiles.forEach { array.put(it.serialize()) }
            return array.toString()
        }
    }
}

/**
 * The config profiles: the registry in `app.json`, one `config.json` per profile, and one
 * directory per profile under [DesktopStorageLayout].
 *
 * Also the place a new profile's root directory is decided. `PlatformUtilsImpl.getDefaultRootDir()`
 * is a fixed `~/.shttps/www` and cannot answer per profile, so [createProfile] sets the root
 * explicitly and that default is left to serve the legacy profile, whose `www` it already names.
 */
class ConfigProfiles(
    private val appSettingsDir: File,
    private val appState: DesktopAppState,
    legacyProfileName: String = DEFAULT_LEGACY_PROFILE_NAME,
) {
    private val logger = SHTTPSLoggerProxy.getLogger(javaClass)
    private val profiles: MutableList<ConfigProfile>
    private var activeId: String

    init {
        val loaded = ConfigProfile.deserializeAll(appState.profilesJson)
        if (loaded.isEmpty()) {
            //the profile that came before profiles: its config is the one the app has always
            //opened, so seeding moves no files and can be repeated safely
            loaded.add(
                ConfigProfile(
                    id = DesktopStorageLayout.MAIN_PROFILE_ID,
                    name = legacyProfileName,
                    store = DesktopStorageLayout
                        .configFile(appSettingsDir, DesktopStorageLayout.MAIN_PROFILE_ID)
                        .absolutePath,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        profiles = loaded
        val stored = appState.activeProfileId
        //an active id naming no entry would leave the app with no config at all
        activeId = if (profiles.any { it.id == stored }) stored!! else profiles[0].id
        persist()
    }

    // --- reading -------------------------------------------------------------------------------

    fun all(): List<ConfigProfile> = profiles.toList()

    val count: Int get() = profiles.size

    val activeProfileId: String get() = activeId

    fun active(): ConfigProfile = byId(activeId) ?: profiles[0]

    fun byId(id: String?): ConfigProfile? = profiles.firstOrNull { it.id == id }

    fun profileDir(profileId: String): File =
        DesktopStorageLayout.profileDir(appSettingsDir, profileId)

    fun wwwDir(profileId: String): File = DesktopStorageLayout.wwwDir(appSettingsDir, profileId)

    fun databasesDir(profileId: String): File =
        DesktopStorageLayout.databasesDir(appSettingsDir, profileId)

    fun activeDatabasesDir(): File = databasesDir(activeId)

    fun ownsDeletableDataDir(profileId: String): Boolean =
        DesktopStorageLayout.ownsDeletableDataDir(appSettingsDir, profileId)

    /**
     * A profile may go only if it is neither the one being served nor the only one left: the first
     * would leave the app without a config mid-flight, the second with nothing to switch to.
     */
    fun canDelete(profileId: String): Boolean =
        profileId != activeId && profiles.size > 1 && byId(profileId) != null

    /** Whether a picked folder may be served. See [ServedFolderGuard]. */
    fun isFolderAllowedToServe(folder: File?): Boolean =
        ServedFolderGuard.isAllowed(appSettingsDir, activeId, profiles.map { it.id }, folder)

    // --- opening -------------------------------------------------------------------------------

    /**
     * Opens a profile's config, filling in its own `www` directory when no root has been chosen
     * yet. Throws whatever [AppConfig] throws for an unreadable file - the caller decides what to
     * do about it, because opening a profile is also how a switch begins and a switch must fail
     * before anything is torn down.
     */
    fun openConfig(profile: ConfigProfile): AppConfig {
        val file = File(profile.store)
        file.absoluteFile.parentFile?.mkdirs()
        val config = AppConfig(file, appState)
        if (config.rootDir == null) {
            val www = wwwDir(profile.id)
            if (www.isDirectory || www.mkdirs()) {
                config.setRootDir(www.absolutePath)
            } else {
                logger.e("Can't create the root directory ${www.absolutePath} " +
                        "for profile ${profile.id}")
            }
        }
        return config
    }

    fun openActiveConfig(): AppConfig = openConfig(active())

    // --- writing -------------------------------------------------------------------------------

    /** Creates a profile with default settings and its own directory, or null on failure. */
    fun createProfile(name: String): ConfigProfile? {
        val id = generateId(System.currentTimeMillis())
        val profile = ConfigProfile(
            id = id,
            name = name.trim(),
            store = DesktopStorageLayout.configFile(appSettingsDir, id).absolutePath,
            createdAt = System.currentTimeMillis(),
        )
        val www = wwwDir(id)
        val databases = databasesDir(id)
        if (!ensureDir(www) || !ensureDir(databases)) {
            logger.e("Can't create the directories of the new profile $id")
            return null
        }
        //set the root here rather than leaving it to openConfig, so that the profile is complete
        //before anything serves it
        try {
            AppConfig(File(profile.store), appState).setRootDir(www.absolutePath)
        } catch (e: Exception) {
            logger.e("Can't create the config file of the new profile $id", e)
            return null
        }
        profiles.add(profile)
        persist()
        return profile
    }

    fun renameProfile(profileId: String, name: String): Boolean {
        val profile = byId(profileId) ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        profile.name = trimmed
        persist()
        return true
    }

    /**
     * Removes a profile, optionally with its data.
     *
     * [deleteData] is honoured only for a profile that owns a directory under the profiles root -
     * the legacy profile's directory is `~/.shttps`, which holds the registry itself. A database
     * or a TLS keystore the user picked somewhere else is left where it is; it is not this
     * profile's to delete.
     */
    fun deleteProfile(profileId: String, deleteData: Boolean): Boolean {
        if (!canDelete(profileId)) return false
        val profile = byId(profileId) ?: return false
        if (deleteData && ownsDeletableDataDir(profileId)) {
            //takes the SQLite files with their -wal/-shm companions along with everything else
            deleteRecursively(profileDir(profileId))
        } else {
            //at least the config file, which is this profile and nothing else
            val configFile = File(profile.store)
            if (configFile.isFile && !configFile.delete()) {
                logger.w("Can't delete ${configFile.absolutePath}")
            }
        }
        profiles.remove(profile)
        persist()
        return true
    }

    fun setActiveProfile(profileId: String): Boolean {
        if (byId(profileId) == null) return false
        activeId = profileId
        persist()
        return true
    }

    // --- internals -----------------------------------------------------------------------------

    /** Writes only what changed, so an unchanged registry costs nothing on launch. */
    private fun persist() {
        val json = ConfigProfile.serializeAll(profiles)
        if (json != appState.profilesJson) appState.profilesJson = json
        if (activeId != appState.activeProfileId) appState.activeProfileId = activeId
    }

    /**
     * `p` plus a base-36 timestamp: short enough to read in a path, and never equal to `main`. A
     * taken id means two profiles created inside the same millisecond, so the next free
     * millisecond is used rather than retrying the same one.
     */
    private fun generateId(nowMillis: Long): String {
        var candidate = nowMillis.coerceAtLeast(0L)
        while (true) {
            val id = "p" + candidate.toString(36)
            if (!DesktopStorageLayout.isMainProfile(id) && byId(id) == null) return id
            candidate++
        }
    }

    private fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    /**
     * Deletes a tree without descending through a symbolic link - listing a link to a directory
     * hands back the target's children, so recursing into one would delete files outside the
     * profile. The link itself is removed.
     */
    private fun deleteRecursively(file: File) {
        val link = try {
            java.nio.file.Files.isSymbolicLink(file.toPath())
        } catch (e: Exception) {
            true
        }
        if (!link) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        if (!file.delete()) logger.w("Can't delete ${file.absolutePath}")
    }

    companion object {
        const val DEFAULT_LEGACY_PROFILE_NAME: String = "Default"
    }
}
