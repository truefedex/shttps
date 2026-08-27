package com.phlox.simpleserver.screens.attributions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The operating systems an [Attribution] applies to.
 *
 * This exists because a build's dependency set is not the same everywhere: the closed PLUS build
 * decides at packaging time which native artifacts it carries (FFmpeg on Windows and Linux, the
 * D-Bus stack on Linux, neither on macOS - see desktop-plus/build.gradle), and jpackage cannot
 * cross build, so the machine that packaged an installer is the platform that installer is for.
 * Listing FFmpeg to a macOS user would name a component their copy genuinely does not contain.
 */
@Serializable
enum class AttributionPlatform {
    @SerialName("windows") WINDOWS,
    @SerialName("macos") MACOS,
    @SerialName("linux") LINUX;

    companion object {
        /**
         * The platform this JVM runs on, or null when `os.name` is something we do not recognise -
         * which [Attributions] deliberately treats as "show everything" rather than "show nothing".
         */
        fun current(osName: String? = System.getProperty("os.name")): AttributionPlatform? {
            val name = osName?.lowercase() ?: return null
            return when {
                name.contains("win") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MACOS
                name.contains("nux") || name.contains("nix") || name.contains("aix") -> LINUX
                else -> null
            }
        }
    }
}

/**
 * One third-party component distributed with this application.
 *
 * Deliberately not localized and deliberately not in `strings.xml`: the name, the copyright line
 * and the licence are legal text that has to be reproduced as its author wrote it, and the closed
 * build has to be able to ship entries of its own without touching this module (see
 * [com.phlox.simpleserver.ext.DesktopExtension.attributionsResource]).
 */
@Serializable
data class Attribution(
    /** Component name as its authors spell it, e.g. "kotlinx.coroutines". */
    val name: String,
    /** The version shipped, when there is a meaningful one. */
    val version: String? = null,
    /** The copyright line, reproduced verbatim. */
    val copyright: String? = null,
    /** Human readable licence name, e.g. "Apache License 2.0". */
    val license: String,
    /** Project home page. */
    val url: String? = null,
    /** Where the licence text itself can be read. */
    val licenseUrl: String? = null,
    /**
     * Where the component's source can be obtained. Not decoration for the LGPL entries - the
     * licence requires the offer, and [AttributionsTest] fails the build without it.
     */
    val sourceUrl: String? = null,
    /** Anything the user needs to know, e.g. that a library is the host's rather than ours. */
    val note: String? = null,
    /** The platforms this entry applies to. Empty - the common case - means all of them. */
    val platforms: Set<AttributionPlatform> = emptySet()
) {
    /** Name and version as one line, for the card heading. */
    val displayName: String get() = if (version.isNullOrBlank()) name else "$name $version"

    fun appliesTo(platform: AttributionPlatform?): Boolean =
        platforms.isEmpty() || platform == null || platform in platforms
}

/** Root of an attributions JSON resource. */
@Serializable
data class AttributionsFile(
    val attributions: List<Attribution> = emptyList()
)
