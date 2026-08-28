package com.phlox.simpleserver.updates

import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.simpleserver.buildconfig.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Asks GitHub whether a newer release of this application exists.
 *
 * Deliberately depends on nothing but the project's own GitHub repository - no website, no other
 * service - so that the check keeps working for as long as the releases do, with nothing to
 * maintain in between. Only the open source desktop build ever calls this: the PLUS build is
 * delivered through application stores that update it themselves, which is what
 * [com.phlox.simpleserver.ext.DesktopExtensions.updateChecksSupported] gates.
 *
 * Nothing here downloads or installs anything. The user is told, and sent to the release page.
 *
 * **One request does the whole job.** `releases/latest` carries the version (`tag_name`), the page
 * to send the user to (`html_url`) *and* the release notes (`body`), because the release workflow
 * publishes RELEASE_NOTES.md as the release body (`body_path:` in build_n_release.yml). Reading the
 * notes out of the release rather than out of a branch is also the more correct of the two: the
 * body is pinned to the version it describes, where the file on a branch is whatever is being
 * prepared next. `releases/latest` additionally excludes drafts and prereleases for us.
 */
object GitHubUpdateChecker {
    private val logger = SHTTPSLoggerProxy.getLogger("GitHubUpdateChecker")

    /** Where releases are published. Nothing else in this application talks to GitHub. */
    const val RELEASES_API_URL = "https://api.github.com/repos/truefedex/shttps/releases/latest"

    /** Landing page, used when the API could not be reached but the user asked to go there anyway. */
    const val RELEASES_PAGE_URL = "https://github.com/truefedex/shttps/releases"

    /**
     * How long a successful check is good for. Once a day is generous for a project that releases
     * every few months, and keeps the application far below GitHub's unauthenticated rate limit
     * even for a user who launches it constantly.
     */
    const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    //A release object is ~15 KB today and grows mainly with the assets array - one nested object
    //per published artifact - so this is roughly thirty times the current size. Exceeding it is
    //treated as a failed check rather than truncated, see fetchLatestRelease
    private const val MAX_RESPONSE_BYTES = 512 * 1024

    //lenient about unknown keys, and it is not optional here: a GitHub release object carries
    //some thirty fields (author, assets, upload_url, ...) of which we want exactly three
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null
    )

    /**
     * Fetches the newest published release, or null if anything at all went wrong.
     *
     * Failures are logged and swallowed on purpose. An update check is something the application
     * does on its own initiative, so a flaky network, an offline machine or a GitHub outage must
     * never produce anything the user has to dismiss. The one caller that *did* ask - the manual
     * button on the version info screen - distinguishes null from "up to date" itself.
     */
    suspend fun fetchLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        var connection: HttpsURLConnection? = null
        try {
            //URI().toURL() rather than the URL(String) constructor, which is deprecated since 20
            connection = (URI(RELEASES_API_URL).toURL().openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                //GitHub requires a User-Agent and answers 403 to some clients without one
                setRequestProperty("User-Agent", "SHTTPS-Desktop/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code != HttpsURLConnection.HTTP_OK) {
                logger.w("Update check got HTTP $code from $RELEASES_API_URL")
                return@withContext null
            }
            //one byte past the cap, so that hitting it is distinguishable from a response that
            //merely fills it. Truncating silently would hand the parser invalid JSON and the
            //failure would read as a SerializationException forever after - the same shape of
            //quietly-stopped-working bug as an inverted throttle
            val bytes = connection.inputStream.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
            if (bytes.size > MAX_RESPONSE_BYTES) {
                logger.w("Update check: response larger than $MAX_RESPONSE_BYTES bytes, ignoring it")
                return@withContext null
            }
            parseRelease(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            logger.w("Update check failed: $e")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Turns a `releases/latest` payload into the three fields this application uses, or null if it
     * carries no version anybody could compare against. Separate from the fetch above so that it
     * can be tested against a captured payload without a network.
     */
    internal fun parseRelease(payload: String): LatestRelease? {
        val release = json.decodeFromString<GitHubRelease>(payload)
        val versionName = normalizeVersionName(release.tagName)
        if (versionName.isEmpty()) {
            logger.w("Update check: release carries no usable tag name (\"${release.tagName}\")")
            return null
        }
        return LatestRelease(
            versionName = versionName,
            releaseNotes = release.body?.trim().orEmpty(),
            //a release page is better than nothing if GitHub ever omits the field
            releaseUrl = release.htmlUrl.ifEmpty { RELEASES_PAGE_URL }
        )
    }

    /**
     * True when [candidate] names a version later than [current].
     *
     * Compares version *names*, because a GitHub release has no counterpart to the app's
     * VERSION_CODE - only the tag it was cut from.
     */
    fun isNewerThan(candidate: String, current: String): Boolean =
        compareVersionNames(candidate, current) > 0

    /**
     * Compares two dotted version names the way a human reads them, so that 1.10.0 is newer than
     * 1.9.0 - which a plain string comparison gets backwards.
     *
     * Tolerant on input by design: it is fed release tags, which have carried a "v" prefix and, in
     * this project's older tags, a "v." one. Anything non-numeric in a component is dropped rather
     * than throwing (so "3.5.0-beta1" compares as 3.5.0), and a missing component counts as zero,
     * so "3.4" and "3.4.0" are the same version. A tag nobody can parse compares as 0, which is
     * older than any real release and therefore prompts nobody.
     */
    fun compareVersionNames(a: String, b: String): Int {
        val left = versionComponents(a)
        val right = versionComponents(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val result = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    /** Strips a leading "v"/"v." and any other decoration, leaving "3.4.3". */
    fun normalizeVersionName(tag: String): String {
        val digits = tag.indexOfFirst { it.isDigit() }
        if (digits < 0) return ""
        return tag.substring(digits).trim()
    }

    private fun versionComponents(version: String): List<Int> =
        normalizeVersionName(version)
            .split('.')
            //takeWhile rather than toIntOrNull so that a "0-beta1" style component keeps its
            //numeric head instead of collapsing to zero
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}

/** A published release, reduced to the three things this application does anything with. */
data class LatestRelease(
    val versionName: String,
    val releaseNotes: String,
    val releaseUrl: String
)
