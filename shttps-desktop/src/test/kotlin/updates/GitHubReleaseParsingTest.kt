package com.phlox.simpleserver.updates

import com.phlox.simpleserver.dialogs.formatReleaseNotes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses a real `releases/latest` payload, captured from the project's own repository into
 * `src/test/resources/updates/releases-latest.json`.
 *
 * The point of using the real thing rather than a hand-written fragment is the twenty top level
 * fields (and the nested author and asset objects) that this application wants none of: they are
 * what `ignoreUnknownKeys` has to survive, and a trimmed fixture would stop testing that.
 */
class GitHubReleaseParsingTest {

    private fun fixture(): String = checkNotNull(
        javaClass.getResourceAsStream("/updates/releases-latest.json")
    ) { "test fixture updates/releases-latest.json is missing" }
        .use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Test
    fun `the three fields we use survive a real payload`() {
        val release = assertNotNull(GitHubUpdateChecker.parseRelease(fixture()))

        //the "v" of the tag is gone, so it can be compared with BuildConfig.VERSION_NAME directly
        assertEquals("3.4.3", release.versionName)
        assertEquals(
            "https://github.com/truefedex/shttps/releases/tag/v3.4.3",
            release.releaseUrl
        )
        //the release body is RELEASE_NOTES.md, published by build_n_release.yml as body_path -
        //which is why the update prompt needs no second request to fetch the notes
        assertTrue(release.releaseNotes.startsWith("*"), "expected markdown bullets")
        assertEquals(5, release.releaseNotes.lines().size)
    }

    @Test
    fun `a payload without a usable tag yields nothing`() {
        assertNull(GitHubUpdateChecker.parseRelease("""{"tag_name":"","html_url":"x"}"""))
        assertNull(GitHubUpdateChecker.parseRelease("""{"tag_name":"nightly"}"""))
    }

    @Test
    fun `absent optional fields fall back rather than throwing`() {
        val release = assertNotNull(GitHubUpdateChecker.parseRelease("""{"tag_name":"v9.9.9"}"""))
        assertEquals("9.9.9", release.versionName)
        assertEquals("", release.releaseNotes)
        assertEquals(GitHubUpdateChecker.RELEASES_PAGE_URL, release.releaseUrl)
    }

    @Test
    fun `release notes render as bullet lines`() {
        val release = assertNotNull(GitHubUpdateChecker.parseRelease(fixture()))
        val rendered = formatReleaseNotes(release.releaseNotes)

        assertEquals(5, rendered.lines().size)
        assertTrue(rendered.lines().all { it.startsWith("• ") }, rendered)
        //no markdown renderer is involved, so the source marker must not survive
        assertTrue(rendered.none { it == '*' }, rendered)
    }

    @Test
    fun `a line with no marker is left alone`() {
        assertEquals(
            "• first\n• second\nplain line",
            formatReleaseNotes("* first\n\n- second\nplain line\n")
        )
    }
}
