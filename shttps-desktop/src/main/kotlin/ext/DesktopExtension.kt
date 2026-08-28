package com.phlox.simpleserver.ext

import com.phlox.simpleserver.buildconfig.BuildConfig
import java.io.InputStream
import java.util.ServiceLoader

/**
 * Hook for builds that wrap this application with code that can not live in the open source tree -
 * currently the closed "PLUS" build in the `desktop-plus` module of the umbrella project, but the
 * same mechanism is what any third party would use to ship a customized SHTTPS desktop app.
 *
 * Implementations are picked up from the classpath with [ServiceLoader], so nothing here ever has
 * to know that they exist. To provide one, put the implementation class on the classpath together
 * with a `META-INF/services/com.phlox.simpleserver.ext.DesktopExtension` file naming it.
 *
 * Two rules keep this boundary cheap to implement against:
 *
 * 1. **Only JDK types in the signatures below.** This module declares every dependency of its own
 *    as `implementation`, so none of Compose, kotlinx or server-lib is on the compile classpath of
 *    a module that merely depends on us. A `String?` costs an implementor nothing; a `@Composable`
 *    or a `SHTTPSConfig` would force them to re-declare those dependencies.
 * 2. **Everything is optional.** Every member has a default, so adding a new one never breaks an
 *    existing implementation.
 */
interface DesktopExtension {
    /**
     * Classpath resource holding the HTML end user license agreement this build ships.
     * Null (the default) leaves the open source notice bundled with this module in place.
     */
    val eulaResource: String? get() = null

    /**
     * What the "Build type" row of the version info screen should say, e.g. "Desktop PLUS".
     * Null (the default) keeps [BuildConfig.BUILD_TYPE].
     */
    val editionName: String? get() = null

    /**
     * Classpath resource holding this build's *additional* third-party attributions, as JSON in
     * the shape of [com.phlox.simpleserver.screens.attributions.AttributionsFile].
     *
     * Unlike [eulaResource] this **adds to** the bundled list rather than replacing it - a build
     * that wraps this one ships everything the open build does plus whatever it brought along, so
     * every extension naming a resource is read. An entry may name the operating systems it
     * applies to, which is what keeps a macOS installer from crediting libraries only the Windows
     * and Linux ones carry.
     *
     * Null (the default) means this build adds nothing to the open source list.
     */
    val attributionsResource: String? get() = null

    /**
     * True when starting with the operating system is something the user turns on outside this
     * application - the case for an MSIX package, where autostart is a manifest declared startup
     * task rather than a registry entry this process could write.
     *
     * It changes two things: the settings switch becomes a link to the system settings page named
     * by [autostartSettingsUri], and the app can no longer ask the auto launch library whether it
     * was started automatically, so it falls back to "start hidden when close to tray is on".
     *
     * Null (the default) means this build manages autostart itself.
     */
    val autostartManagedBySystem: Boolean? get() = null

    /**
     * Where to send the user to turn autostart on, when [autostartManagedBySystem] is true.
     * Typically an "ms-settings:" URI. Null keeps the default startup apps page.
     */
    val autostartSettingsUri: String? get() = null
}

/**
 * The extensions found on the classpath, and the resolved value of everything they can override.
 * When several extensions answer the same question the first non-null answer wins.
 */
object DesktopExtensions {
    /** The EULA of the plain open source build. */
    const val DEFAULT_EULA_RESOURCE = "eula/license-oss.html"

    /** Third-party components of the plain open source build. */
    const val DEFAULT_ATTRIBUTIONS_RESOURCE = "attributions/attributions-oss.json"

    /** Windows "Startup apps" settings page. */
    const val DEFAULT_AUTOSTART_SETTINGS_URI = "ms-settings:startupapps"

    //deliberately not the thread context classloader: extensions and the resources they point at
    //have to be looked up by one and the same loader, otherwise a build could be found while the
    //file it names is not
    private val classLoader: ClassLoader = DesktopExtensions::class.java.classLoader

    private val extensions: List<DesktopExtension> by lazy {
        ServiceLoader.load(DesktopExtension::class.java, classLoader).toList()
    }

    val eulaResource: String
        get() = extensions.firstNotNullOfOrNull { it.eulaResource } ?: DEFAULT_EULA_RESOURCE

    val editionName: String
        get() = extensions.firstNotNullOfOrNull { it.editionName } ?: BuildConfig.BUILD_TYPE

    val autostartManagedBySystem: Boolean
        get() = extensions.firstNotNullOfOrNull { it.autostartManagedBySystem } ?: false

    val autostartSettingsUri: String
        get() = extensions.firstNotNullOfOrNull { it.autostartSettingsUri } ?: DEFAULT_AUTOSTART_SETTINGS_URI

    /**
     * Every attributions resource on the classpath, ours first.
     *
     * The one question here answered by *all* extensions rather than by the first one with an
     * opinion, and deliberately so: the others pick between mutually exclusive answers (a build
     * has one EULA, one edition name), while attributions accumulate - a wrapping build ships
     * what we ship plus its own, so taking only the first answer would drop one list or the other.
     */
    val attributionResources: List<String>
        get() = listOf(DEFAULT_ATTRIBUTIONS_RESOURCE) + extensions.mapNotNull { it.attributionsResource }

    /** Reads a resource named by an extension, using the loader that found the extension itself. */
    fun openResource(name: String): InputStream? = classLoader.getResourceAsStream(name)
}
