package com.phlox.simpleserver

import com.phlox.server.utils.SHTTPSLoggerProxy
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * State that belongs to the application rather than to any one server configuration, in
 * `~/.shttps/app.json`.
 *
 * This is the desktop counterpart of the Android app's `ConfigInternal`. Until config profiles
 * existed there was nothing to separate: one config file meant "the settings" and "the app's
 * state" were the same thing, so [AppConfig] grew the window geometry, the tray behaviour and the
 * update-check bookkeeping alongside the server settings. With several configurations those stop
 * making sense per profile - most visibly [autostart], which is not just a flag but a mirror of a
 * single operating-system login item, so a per-profile copy would show "off" while the system
 * still launches the app.
 *
 * The profile registry has to live here for a simpler reason: it is what says which config file to
 * open, so it cannot be inside one.
 *
 * Keys match the ones [AppConfig] used, which is what makes [importLegacyGlobalsOnce] a plain
 * key-by-key copy out of the old config file.
 */
class DesktopAppState(private val file: File) {
    private val logger = SHTTPSLoggerProxy.getLogger(javaClass)
    private val json: JSONObject = read()

    private fun read(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(String(file.readBytes(), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            //losing window geometry and the profile list is recoverable; refusing to start is not
            logger.e("Can't read ${file.absolutePath}, starting with defaults", e)
            JSONObject()
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(json.toString(4).toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.e("Can't write ${file.absolutePath}", e)
        }
    }

    private fun putAndSave(key: String, value: Any?) {
        if (value == null) json.remove(key) else json.put(key, value)
        save()
    }

    // --- the profile registry ------------------------------------------------------------------

    /** The registry as [ConfigProfile] serializes it, or null before it has been seeded. */
    var profilesJson: String?
        get() = json.optString(KEY_PROFILES, "").ifEmpty { null }
        set(value) = putAndSave(KEY_PROFILES, value)

    /** Id of the profile to open on launch. Null before the registry is seeded. */
    var activeProfileId: String?
        get() = json.optString(KEY_ACTIVE_PROFILE_ID, "").ifEmpty { null }
        set(value) = putAndSave(KEY_ACTIVE_PROFILE_ID, value)

    // --- application state ---------------------------------------------------------------------

    var autostart: Boolean
        get() = json.optBoolean(AppConfig.KEY_AUTOSTART, false)
        set(value) = putAndSave(AppConfig.KEY_AUTOSTART, value)

    var closeToTray: Boolean
        get() = json.optBoolean(AppConfig.KEY_CLOSE_TO_TRAY, false)
        set(value) = putAndSave(AppConfig.KEY_CLOSE_TO_TRAY, value)

    var networkInterfaceToShowIpFrom: Int
        get() = json.optInt(AppConfig.KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM, -1)
        set(value) = putAndSave(AppConfig.KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM, value)

    var ipVersionToShow: Int
        get() = json.optInt(AppConfig.KEY_IP_VERSION_TO_SHOW, 4)
        set(value) = putAndSave(AppConfig.KEY_IP_VERSION_TO_SHOW, value)

    var collapsedSections: Set<String>
        get() {
            val value = json.optString(AppConfig.KEY_COLLAPSED_SECTIONS, "")
            if (value.isEmpty()) return emptySet()
            return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = putAndSave(
            AppConfig.KEY_COLLAPSED_SECTIONS,
            if (value.isEmpty()) null else value.joinToString(",")
        )

    var updateCheckEnabled: Boolean
        get() = json.optBoolean(AppConfig.KEY_UPDATE_CHECK_ENABLED, true)
        set(value) = putAndSave(AppConfig.KEY_UPDATE_CHECK_ENABLED, value)

    var lastUpdateCheckTime: Long
        get() = json.optString(AppConfig.KEY_LAST_UPDATE_CHECK_TIME, "").toLongOrNull() ?: 0L
        set(value) = putAndSave(AppConfig.KEY_LAST_UPDATE_CHECK_TIME, value.toString())

    var skippedUpdateVersion: String?
        get() = json.optString(AppConfig.KEY_SKIPPED_UPDATE_VERSION, "").ifEmpty { null }
        set(value) = putAndSave(AppConfig.KEY_SKIPPED_UPDATE_VERSION, value)

    val windowWidth: Int
        get() = json.optInt(AppConfig.KEY_WINDOW_WIDTH, AppConfig.DEFAULT_WINDOW_WIDTH_DP)

    val windowHeight: Int
        get() = json.optInt(AppConfig.KEY_WINDOW_HEIGHT, AppConfig.DEFAULT_WINDOW_HEIGHT_DP)

    /** One setter for both, because a resize always changes the pair and each save rewrites it. */
    fun setWindowSize(width: Int, height: Int) {
        if (width == windowWidth && height == windowHeight) return
        json.put(AppConfig.KEY_WINDOW_WIDTH, width)
        json.put(AppConfig.KEY_WINDOW_HEIGHT, height)
        save()
    }

    // --- migration -----------------------------------------------------------------------------

    /**
     * Copies the application-level values out of the config file they used to share with the
     * server settings. Runs once, then records that it did, so later edits to this file are not
     * overwritten by stale values still sitting in the old one.
     *
     * The keys are deliberately left in the old config file: nothing reads them any more, and
     * removing them would rewrite a file the user may be running a server from.
     */
    fun importLegacyGlobalsOnce(legacyConfigFile: File) {
        if (json.optInt(KEY_VERSION, 0) >= VERSION) return
        if (legacyConfigFile.isFile) {
            try {
                val legacy = JSONObject(
                    String(legacyConfigFile.readBytes(), StandardCharsets.UTF_8))
                for (key in LEGACY_GLOBAL_KEYS) {
                    if (legacy.has(key) && !json.has(key)) {
                        json.put(key, legacy.get(key))
                    }
                }
            } catch (e: Exception) {
                logger.e("Can't read the legacy config to import app settings from", e)
            }
        }
        json.put(KEY_VERSION, VERSION)
        save()
    }

    companion object {
        const val FILE_NAME: String = "app.json"

        private const val KEY_VERSION = "app_state_version"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

        private const val VERSION = 1

        /** The keys that moved out of the server config, in the spelling they had there. */
        private val LEGACY_GLOBAL_KEYS = listOf(
            AppConfig.KEY_AUTOSTART,
            AppConfig.KEY_CLOSE_TO_TRAY,
            AppConfig.KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM,
            AppConfig.KEY_IP_VERSION_TO_SHOW,
            AppConfig.KEY_COLLAPSED_SECTIONS,
            AppConfig.KEY_UPDATE_CHECK_ENABLED,
            AppConfig.KEY_LAST_UPDATE_CHECK_TIME,
            AppConfig.KEY_SKIPPED_UPDATE_VERSION,
            AppConfig.KEY_WINDOW_WIDTH,
            AppConfig.KEY_WINDOW_HEIGHT,
        )
    }
}
