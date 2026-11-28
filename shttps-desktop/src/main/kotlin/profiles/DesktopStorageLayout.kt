package com.phlox.simpleserver.profiles

import java.io.File

/**
 * Where each config profile keeps its files, and the path comparisons that keep profiles from
 * serving each other.
 *
 * The shape is the same as the Android app's `StorageLayout`: a profile owns a directory, and its
 * config file, served root and databases are all derived from it. The profile that existed before
 * profiles did owns `~/.shttps` itself, which is what keeps it an ordinary registry entry.
 *
 * Most of this comes for free on this platform. `SHTTPSConfigImpl.getDefaultDatabasePath()` and
 * `installTLSCertBytes()` already resolve against the directory holding the config file - written
 * that way because the command line build takes an arbitrary `--config` and "two servers started
 * from two configs must not end up sharing one SQLite file". Giving each profile its own directory
 * is exactly the case they were written for, so the database and the imported certificate follow a
 * profile with no further work. `PlatformUtilsImpl.getDefaultRootDir()` is the one that does not:
 * it is a fixed `~/.shttps/www`, which happens to be precisely the legacy profile's own `www`, so
 * it stays correct as that profile's default while every new profile has its root set explicitly
 * when it is created.
 */
object DesktopStorageLayout {
    /**
     * Id of the profile that was the only one before this feature existed. Its config is the
     * `~/.shttps/config.json` that the command line build still defaults to, which is why it stays
     * where it is instead of moving under [PROFILES_DIR_NAME].
     */
    const val MAIN_PROFILE_ID: String = "main"

    const val PROFILES_DIR_NAME: String = "profiles"
    const val WWW_DIR_NAME: String = "www"
    const val DATABASES_DIR_NAME: String = "databases"
    const val CONFIG_FILE_NAME: String = "config.json"

    fun isMainProfile(profileId: String?): Boolean = profileId == MAIN_PROFILE_ID

    fun profilesRoot(appSettingsDir: File): File = File(appSettingsDir, PROFILES_DIR_NAME)

    /** The directory a profile owns. `~/.shttps` itself for the legacy one. */
    fun profileDir(appSettingsDir: File, profileId: String): File =
        if (isMainProfile(profileId)) appSettingsDir
        else File(profilesRoot(appSettingsDir), profileId)

    fun configFile(appSettingsDir: File, profileId: String): File =
        File(profileDir(appSettingsDir, profileId), CONFIG_FILE_NAME)

    fun wwwDir(appSettingsDir: File, profileId: String): File =
        File(profileDir(appSettingsDir, profileId), WWW_DIR_NAME)

    fun databasesDir(appSettingsDir: File, profileId: String): File =
        File(profileDir(appSettingsDir, profileId), DATABASES_DIR_NAME)

    /**
     * Whether removing this profile may take its directory with it: only a directory strictly
     * inside [profilesRoot]. The legacy profile owns `~/.shttps`, which holds the registry, the
     * lock files and everything else, so its data is never deleted by a profile removal.
     */
    fun ownsDeletableDataDir(appSettingsDir: File, profileId: String): Boolean =
        !isMainProfile(profileId) &&
                isStrictlyInside(profilesRoot(appSettingsDir), profileDir(appSettingsDir, profileId))

    /** Canonical path, falling back to the absolute one, so that two spellings compare equal. */
    fun canonicalPath(file: File): String = try {
        file.canonicalPath
    } catch (e: Exception) {
        file.absolutePath
    }

    fun isSamePath(a: File?, b: File?): Boolean {
        if (a == null || b == null) return false
        return canonicalPath(a) == canonicalPath(b)
    }

    /**
     * Whether [descendant] is inside [ancestor], not counting them being the same directory. A
     * separator is appended so that `~/.shttps` is not taken to contain `~/.shttps-backup`.
     */
    fun isStrictlyInside(ancestor: File?, descendant: File?): Boolean {
        if (ancestor == null || descendant == null) return false
        var a = canonicalPath(ancestor)
        val d = canonicalPath(descendant)
        if (a == d) return false
        if (!a.endsWith(File.separator)) a += File.separator
        return d.startsWith(a)
    }

    fun isSameOrInside(ancestor: File?, descendant: File?): Boolean =
        isSamePath(ancestor, descendant) || isStrictlyInside(ancestor, descendant)
}

/**
 * Keeps a folder the user picked from turning the app's own data directory into a web root.
 *
 * The profile layout rests on one claim - a profile's directory is never inside the folder being
 * served - and nothing holds that claim after setup, because the root and the CGI folder are the
 * user's to choose at any time. Picking `~/.shttps` would serve every profile's `config.json`, and
 * those hold the TLS keystore passwords in plain text: this platform does not override
 * `SHTTPSConfig.getSecretString`, so there is nothing encrypting them.
 *
 * ### What it refuses, and what it deliberately does not
 * Only a pick that *aims at* the app's own data: the data directory itself, the directory holding
 * the profiles, a profile's own directory, or another profile's databases. Those are accidents -
 * there is no reason to serve them and every reason not to.
 *
 * A folder that merely *contains* the data directory, such as the home directory or a drive root,
 * is allowed. Those already exposed `~/.shttps/config.json` before profiles were a thing, so
 * refusing them now would take away a capability rather than close a hole.
 *
 * Nor is it a cage over the user's own content inside the data directory: `~/.shttps/www` is the
 * normal root, another profile's `www` is a deliberate choice to share files, and the live
 * profile's databases folder picked by name is the user serving their own database.
 */
object ServedFolderGuard {
    fun isAllowed(
        appSettingsDir: File,
        activeProfileId: String,
        profileIds: Collection<String>,
        folder: File?
    ): Boolean {
        if (folder == null) return true

        // the data directory itself: it holds every profile's config and the legacy database
        if (DesktopStorageLayout.isSamePath(folder, appSettingsDir)) return false

        // the directory that holds every profile
        if (DesktopStorageLayout.isSamePath(
                folder, DesktopStorageLayout.profilesRoot(appSettingsDir))) {
            return false
        }

        for (id in profileIds) {
            // a profile's own directory holds its config.json. The legacy profile's directory IS
            // the data directory, refused above - and it must not be treated as a profile
            // directory here, or every folder inside it would go with it
            if (!DesktopStorageLayout.isMainProfile(id) &&
                DesktopStorageLayout.isSamePath(
                    folder, DesktopStorageLayout.profileDir(appSettingsDir, id))
            ) {
                return false
            }
            // somebody else's databases, at any depth
            if (id != activeProfileId &&
                DesktopStorageLayout.isSameOrInside(
                    DesktopStorageLayout.databasesDir(appSettingsDir, id), folder)
            ) {
                return false
            }
        }
        return true
    }
}
