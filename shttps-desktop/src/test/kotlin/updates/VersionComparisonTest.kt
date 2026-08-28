package com.phlox.simpleserver.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of the update check that can go wrong quietly: a comparison that gets it backwards
 * either nags every user forever or never tells anybody anything, and neither shows up in a build.
 */
class VersionComparisonTest {

    @Test
    fun `tag prefix is ignored`() {
        assertEquals(0, GitHubUpdateChecker.compareVersionNames("v3.4.3", "3.4.3"))
        //the spelling this project's oldest tags used
        assertEquals(0, GitHubUpdateChecker.compareVersionNames("v.1.10.1", "1.10.1"))
        assertEquals("3.4.3", GitHubUpdateChecker.normalizeVersionName("v3.4.3"))
        assertEquals("1.10.1", GitHubUpdateChecker.normalizeVersionName("v.1.10.1"))
    }

    @Test
    fun `newer releases are recognised`() {
        assertTrue(GitHubUpdateChecker.isNewerThan("v3.4.4", "3.4.3"))
        assertTrue(GitHubUpdateChecker.isNewerThan("v3.5.0", "3.4.3"))
        assertTrue(GitHubUpdateChecker.isNewerThan("v4.0.0", "3.4.3"))
    }

    @Test
    fun `the same or older releases prompt nobody`() {
        assertFalse(GitHubUpdateChecker.isNewerThan("v3.4.3", "3.4.3"))
        assertFalse(GitHubUpdateChecker.isNewerThan("v3.4.2", "3.4.3"))
        assertFalse(GitHubUpdateChecker.isNewerThan("v2.9.9", "3.4.3"))
    }

    @Test
    fun `components are compared as numbers, not as text`() {
        //the case a plain string comparison gets backwards, and the reason this is not one line
        assertTrue(GitHubUpdateChecker.isNewerThan("1.10.0", "1.9.0"))
        assertFalse(GitHubUpdateChecker.isNewerThan("1.9.0", "1.10.0"))
        assertTrue(GitHubUpdateChecker.isNewerThan("3.4.10", "3.4.9"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, GitHubUpdateChecker.compareVersionNames("3.4", "3.4.0"))
        assertTrue(GitHubUpdateChecker.isNewerThan("3.4.1", "3.4"))
        assertFalse(GitHubUpdateChecker.isNewerThan("3.4", "3.4.1"))
    }

    @Test
    fun `a suffixed component keeps its numeric head`() {
        assertEquals(0, GitHubUpdateChecker.compareVersionNames("3.5.0-beta1", "3.5.0"))
        assertTrue(GitHubUpdateChecker.isNewerThan("3.5.0-rc1", "3.4.3"))
    }

    @Test
    fun `unparseable input is older than everything and throws nothing`() {
        assertEquals("", GitHubUpdateChecker.normalizeVersionName("nightly"))
        assertFalse(GitHubUpdateChecker.isNewerThan("nightly", "3.4.3"))
        assertFalse(GitHubUpdateChecker.isNewerThan("", "3.4.3"))
        //and a real release still beats it, so a bad tag can never silence a good one afterwards
        assertTrue(GitHubUpdateChecker.isNewerThan("3.4.4", "nightly"))
    }
}
