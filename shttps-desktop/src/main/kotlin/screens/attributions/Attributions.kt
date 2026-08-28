package com.phlox.simpleserver.screens.attributions

import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.simpleserver.ext.DesktopExtensions
import kotlinx.serialization.json.Json

/**
 * The third-party components this build ships, gathered from every attributions resource on the
 * classpath and filtered down to the platform this copy actually runs on.
 *
 * The open source list is bundled with this module; a wrapping build adds its own through
 * [com.phlox.simpleserver.ext.DesktopExtension.attributionsResource]. Nothing here knows which
 * builds exist - the resources are named by whoever ships them, and read through
 * [DesktopExtensions.openResource] so that a resource is always looked up by the same class
 * loader that found the extension naming it.
 */
object Attributions {
    private val logger = SHTTPSLoggerProxy.getLogger("Attributions")

    //lenient about unknown keys so that an extension shipping a newer schema than the core it is
    //running against degrades to "some fields ignored" instead of an empty screen
    private val json = Json { ignoreUnknownKeys = true }

    /** Everything declared anywhere, unfiltered. Exposed for tests and for [forPlatform]. */
    val all: List<Attribution> by lazy {
        DesktopExtensions.attributionResources.flatMap { load(it) }
    }

    /** What the running platform should be shown, sorted case-insensitively by name. */
    val current: List<Attribution> by lazy { forPlatform(AttributionPlatform.current()) }

    /**
     * [all], narrowed to one platform. A null platform - an `os.name` we do not recognise - keeps
     * everything: an over-inclusive attributions list is a far smaller failure than an empty one.
     */
    fun forPlatform(platform: AttributionPlatform?): List<Attribution> =
        all.filter { it.appliesTo(platform) }
            //two resources can legitimately name the same library - a wrapping build depends on
            //something we already ship - and crediting it twice on one screen looks like a bug
            .distinctBy { it.name to it.version }
            .sortedBy { it.name.lowercase() }

    /**
     * Reads one resource. A missing or malformed file costs its own entries and nothing else -
     * this screen must never be the reason the application fails to open.
     */
    internal fun load(resource: String): List<Attribution> = try {
        val text = DesktopExtensions.openResource(resource)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text == null) {
            logger.w("Attributions resource not found on the classpath: $resource")
            emptyList()
        } else {
            json.decodeFromString<AttributionsFile>(text).attributions
        }
    } catch (e: Exception) {
        logger.e("Failed to read attributions resource $resource", e)
        emptyList()
    }
}
