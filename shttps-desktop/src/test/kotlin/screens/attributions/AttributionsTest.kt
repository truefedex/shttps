package com.phlox.simpleserver.screens.attributions

import com.phlox.simpleserver.ext.DesktopExtensions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the bundled attributions resource and the platform filtering above it. Both matter
 * legally rather than cosmetically: a missing source offer on an LGPL entry is a licence breach,
 * and an entry tagged for the wrong platform credits software the user's copy does not contain.
 */
class AttributionsTest {

    private val bundled: List<Attribution> =
        Attributions.load(DesktopExtensions.DEFAULT_ATTRIBUTIONS_RESOURCE)

    @Test
    fun `bundled resource parses and is not empty`() {
        assertTrue(bundled.isNotEmpty(), "the open source attributions resource produced no entries")
    }

    @Test
    fun `every entry has a name and a licence`() {
        bundled.forEach {
            assertTrue(it.name.isNotBlank(), "an entry has a blank name")
            assertTrue(it.license.isNotBlank(), "${it.name} has a blank licence")
        }
    }

    @Test
    fun `no entry is listed twice`() {
        val duplicates = bundled.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "listed more than once: $duplicates")
    }

    /**
     * The LGPL and the GPL both require that the source be obtainable; naming the component is
     * not enough on its own. Anything copyleft therefore has to carry the address.
     */
    @Test
    fun `copyleft entries offer their source`() {
        bundled.filter { it.license.contains("GPL", ignoreCase = true) }.forEach {
            assertFalse(
                it.sourceUrl.isNullOrBlank(),
                "${it.name} is under ${it.license} but names no source"
            )
        }
    }

    @Test
    fun `entries carry usable links`() {
        bundled.forEach {
            listOfNotNull(it.url, it.licenseUrl, it.sourceUrl).forEach { link ->
                assertTrue(link.startsWith("https://"), "${it.name} has a non-https link: $link")
            }
        }
    }

    @Test
    fun `os name maps to a platform`() {
        assertEquals(AttributionPlatform.WINDOWS, AttributionPlatform.current("Windows 11"))
        assertEquals(AttributionPlatform.MACOS, AttributionPlatform.current("Mac OS X"))
        assertEquals(AttributionPlatform.LINUX, AttributionPlatform.current("Linux"))
        assertEquals(null, AttributionPlatform.current("Haiku"))
        assertEquals(null, AttributionPlatform.current(null))
    }

    @Test
    fun `an entry applies to every platform when it names none`() {
        val everywhere = Attribution(name = "everywhere", license = "MIT")
        AttributionPlatform.entries.forEach { assertTrue(everywhere.appliesTo(it)) }
        assertTrue(everywhere.appliesTo(null))
    }

    @Test
    fun `platform filtering keeps only what that platform ships`() {
        val windowsOnly = Attribution(
            name = "windows only", license = "MIT",
            platforms = setOf(AttributionPlatform.WINDOWS)
        )
        val linuxOnly = Attribution(
            name = "linux only", license = "MIT",
            platforms = setOf(AttributionPlatform.LINUX)
        )
        val both = Attribution(
            name = "windows and linux", license = "MIT",
            platforms = setOf(AttributionPlatform.WINDOWS, AttributionPlatform.LINUX)
        )
        val everywhere = Attribution(name = "everywhere", license = "MIT")
        val all = listOf(windowsOnly, linuxOnly, both, everywhere)

        assertEquals(
            setOf("windows only", "windows and linux", "everywhere"),
            all.filter { it.appliesTo(AttributionPlatform.WINDOWS) }.map { it.name }.toSet()
        )
        assertEquals(
            setOf("linux only", "windows and linux", "everywhere"),
            all.filter { it.appliesTo(AttributionPlatform.LINUX) }.map { it.name }.toSet()
        )
        assertEquals(
            setOf("everywhere"),
            all.filter { it.appliesTo(AttributionPlatform.MACOS) }.map { it.name }.toSet()
        )
    }

    /**
     * An `os.name` we do not recognise must not empty the screen - an over-inclusive list is a
     * much smaller failure than crediting nobody at all.
     */
    @Test
    fun `an unknown platform sees everything`() {
        val all = listOf(
            Attribution(name = "a", license = "MIT", platforms = setOf(AttributionPlatform.WINDOWS)),
            Attribution(name = "b", license = "MIT", platforms = setOf(AttributionPlatform.LINUX)),
            Attribution(name = "c", license = "MIT")
        )
        assertEquals(3, all.count { it.appliesTo(null) })
    }

    @Test
    fun `a missing resource costs its own entries and nothing else`() {
        assertEquals(emptyList(), Attributions.load("attributions/there-is-no-such-file.json"))
    }

    @Test
    fun `a malformed resource costs its own entries and nothing else`() {
        //the EULA is HTML, so it stands in for any resource that exists but is not our JSON
        assertEquals(emptyList(), Attributions.load(DesktopExtensions.DEFAULT_EULA_RESOURCE))
    }

    @Test
    fun `the bundled list survives the whole pipeline`() {
        val resolved = Attributions.forPlatform(AttributionPlatform.LINUX)
        assertTrue(resolved.isNotEmpty())
        assertContains(resolved.map { it.name }, "Kotlin Standard Library")
        //sorted case-insensitively by name
        assertEquals(resolved.map { it.name.lowercase() }.sorted(), resolved.map { it.name.lowercase() })
    }
}
