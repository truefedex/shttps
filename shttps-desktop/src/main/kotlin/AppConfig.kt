package com.phlox.simpleserver

import com.phlox.server.SHTTPSConfigImpl
import java.io.File

/**
 * SHTTPSConfigImpl (SHTTPSConfig implementation for J2SE) plus the desktop-specific settings.
 *
 * One of these per config profile. The properties that describe the *application* rather than this
 * configuration - window geometry, tray behaviour, autostart, update-check bookkeeping - are kept
 * by [DesktopAppState] in a file of its own and only surfaced here, so that every existing caller
 * reads them the way it always did while switching profiles does not change them. [autostart] is
 * the clearest case: it mirrors a single operating-system login item, so per-profile copies would
 * disagree with the system.
 *
 * What stays in this file is what genuinely belongs to one configuration: [runningState] ("this
 * profile's server was running when we quit") and [shutdownTimeout], which is a server setting.
 */
class AppConfig(file: File, private val appState: DesktopAppState) : SHTTPSConfigImpl(file) {

    var autostart: Boolean
        get() = appState.autostart
        set(value) {
            appState.autostart = value
        }

    var runningState: Boolean
        get() = getBoolean(KEY_RUNNING_STATE, false)
        set(value) = setBoolean(KEY_RUNNING_STATE, value)

    var shutdownTimeout: Int
        get() = getInt(KEY_SHUTDOWN_TIMEOUT, 15 * 60)
        set(value) = setInt(KEY_SHUTDOWN_TIMEOUT, value)

    var closeToTray: Boolean
        get() = appState.closeToTray
        set(value) {
            appState.closeToTray = value
        }

    var networkInterfaceToShowIpFrom: Int
        get() = appState.networkInterfaceToShowIpFrom
        set(value) {
            appState.networkInterfaceToShowIpFrom = value
        }

    var ipVersionToShow: Int
        get() = appState.ipVersionToShow
        set(value) {
            appState.ipVersionToShow = value
        }

    val tlsCertPath: String?
        get() = getString(KEY_TLS_CERT, null)

    //tags of the settings sections that the user has collapsed. Only collapsed ones are stored
    //because sections are expanded by default (same approach as in the Android app)
    var collapsedSections: Set<String>
        get() = appState.collapsedSections
        set(value) {
            appState.collapsedSections = value
        }

    //Whether to ask GitHub about newer releases on startup. See
    //com.phlox.simpleserver.updates.GitHubUpdateChecker - only the open source build ever does,
    //and even there the user can turn it off from the version info screen.
    var updateCheckEnabled: Boolean
        get() = appState.updateCheckEnabled
        set(value) {
            appState.updateCheckEnabled = value
        }

    var lastUpdateCheckTime: Long
        get() = appState.lastUpdateCheckTime
        set(value) {
            appState.lastUpdateCheckTime = value
        }

    //The one version the user pressed "Skip this version" on. Deliberately a version rather than
    //the Android app's global "do not show again" boolean: skipping 3.5.0 must not also silence
    //3.6.0, which is what leaves that flag impossible to recover from.
    var skippedUpdateVersion: String?
        get() = appState.skippedUpdateVersion
        set(value) {
            appState.skippedUpdateVersion = value
        }

    //Size of the main window, remembered between runs. Stored in the AWT logical units that
    //WindowState.size is expressed in, so nothing needs converting on the way in or out. Read-only
    //properties with one setWindowSize() instead of two var's: a resize always changes both
    //numbers, and each write rewrites the whole file.
    val windowWidth: Int
        get() = appState.windowWidth

    val windowHeight: Int
        get() = appState.windowHeight

    fun setWindowSize(width: Int, height: Int) {
        appState.setWindowSize(width, height)
    }

    companion object {
        const val KEY_AUTOSTART: String = "autostart"
        const val KEY_RUNNING_STATE: String = "running_state"
        const val KEY_SHUTDOWN_TIMEOUT: String = "shutdown_timeout"
        const val KEY_CLOSE_TO_TRAY: String = "close_to_tray"
        const val KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM: String =
            "network_interface_to_show_ip_from"
        const val KEY_IP_VERSION_TO_SHOW: String = "ip_version_to_show"
        const val KEY_COLLAPSED_SECTIONS: String = "collapsed_sections"
        const val KEY_UPDATE_CHECK_ENABLED: String = "update_check_enabled"
        const val KEY_LAST_UPDATE_CHECK_TIME: String = "last_update_check_time"
        const val KEY_SKIPPED_UPDATE_VERSION: String = "skipped_update_version"
        const val KEY_WINDOW_WIDTH: String = "window_width"
        const val KEY_WINDOW_HEIGHT: String = "window_height"

        //Size the main window opens at before the user has ever resized it, in AWT logical units.
        //A remembered size is used as it is, only clamped to the screen - see initialWindowSize()
        //in Main.kt, which also holds the floor it cannot go under
        const val DEFAULT_WINDOW_WIDTH_DP: Int = 560
        const val DEFAULT_WINDOW_HEIGHT_DP: Int = 580
    }
}
