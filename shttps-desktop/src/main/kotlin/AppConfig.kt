package com.phlox.simpleserver

import com.phlox.server.SHTTPSConfigImpl
import java.io.File

//SHTTPSConfigImpl (SHTTPSConfig implementation for J2SE) plus desktop-specific settings
class AppConfig(file: File): SHTTPSConfigImpl(file) {

    var autostart: Boolean
        get() = getBoolean(KEY_AUTOSTART, false)
        set(value) = setBoolean(KEY_AUTOSTART, value)

    var runningState: Boolean
        get() = getBoolean(KEY_RUNNING_STATE, false)
        set(value) = setBoolean(KEY_RUNNING_STATE, value)

    var shutdownTimeout: Int
        get() = getInt(KEY_SHUTDOWN_TIMEOUT, 15 * 60)
        set(value) = setInt(KEY_SHUTDOWN_TIMEOUT, value)

    var closeToTray: Boolean
        get() = getBoolean(KEY_CLOSE_TO_TRAY, false)
        set(value) = setBoolean(KEY_CLOSE_TO_TRAY, value)

    var networkInterfaceToShowIpFrom: Int
        get() = getInt(KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM, -1)
        set(value) = setInt(KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM, value)

    var ipVersionToShow: Int
        get() = getInt(KEY_IP_VERSION_TO_SHOW, 4)
        set(value) = setInt(KEY_IP_VERSION_TO_SHOW, value)

    val tlsCertPath: String?
        get() = getString(KEY_TLS_CERT, null)

    //tags of the settings sections that the user has collapsed. Only collapsed ones are stored
    //because sections are expanded by default (same approach as in the Android app)
    var collapsedSections: Set<String>
        get() {
            val value = getString(KEY_COLLAPSED_SECTIONS, null)
            if (value.isNullOrEmpty()) return emptySet()
            return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = setString(
            KEY_COLLAPSED_SECTIONS,
            if (value.isEmpty()) null else value.joinToString(",")
        )

    companion object {
        const val KEY_AUTOSTART: String = "autostart"
        const val KEY_RUNNING_STATE: String = "running_state"
        const val KEY_SHUTDOWN_TIMEOUT: String = "shutdown_timeout"
        const val KEY_CLOSE_TO_TRAY: String = "close_to_tray"
        const val KEY_NETWORK_INTERFACE_TO_SHOW_IP_FROM: String =
            "network_interface_to_show_ip_from"
        const val KEY_IP_VERSION_TO_SHOW: String = "ip_version_to_show"
        const val KEY_COLLAPSED_SECTIONS: String = "collapsed_sections"
    }
}