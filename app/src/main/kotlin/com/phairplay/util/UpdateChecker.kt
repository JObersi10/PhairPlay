package com.phairplay.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateChecker — asks GitHub whether a newer release exists, and installs it.
 *
 * A Fire TV has no browser worth the name, so "here is a link to the release page" would be a dead
 * end. Instead the whole path lives here: read the latest release, compare versions, download the
 * APK asset, and hand it to the package installer.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: install anything on its own, or touch Android at all. The
 * download and the install Intent live in the settings screen; this object is pure JVM so the
 * version comparison stays covered by the protocol test suite, which compiles app sources WITHOUT
 * AndroidX. Importing FileProvider here broke that build -- see test-runner/build.gradle.kts.
 *
 * No check runs unless the user opens the settings row. Silent background self-updating is not a
 * thing a sideloaded receiver app should be doing to someone's television.
 */
object UpdateChecker {

    /** The repo releases are published from — the user's fork, not the upstream one. */
    private const val RELEASES_URL = "https://api.github.com/repos/JObersi10/PhairPlay/releases/latest"

    /**
     * The rolling prerelease every push to `main` republishes — the beta channel.
     *
     * A SEPARATE endpoint, not a filter on the list, because GitHub deliberately excludes
     * prereleases from `/releases/latest` and that exclusion is what keeps dev builds away from
     * people who did not ask for them. Asking for the tag by name is the only way to see it.
     */
    private const val DEV_RELEASE_URL = "https://api.github.com/repos/JObersi10/PhairPlay/releases/tags/dev"

    /** Finds the commit SHA CI stamps into the dev release's name. */
    private val SHA_PATTERN = Regex("\\b[0-9a-f]{7,40}\\b")

    sealed interface Result {
        /** A newer release exists. [tag] is what to show; [assetUrl] may be null if none was attached. */
        data class Available(val tag: String, val notes: String, val assetUrl: String?) : Result
        data class UpToDate(val tag: String) : Result
        /** Anything went wrong. [reason] is shown to the user rather than swallowed. */
        data class Failed(val reason: String) : Result
    }

    /**
     * [flavor] is the build variant this APK was produced from — `firetv` or `googletv`.
     *
     * It is not optional. The two flavours are SEPARATE APPLICATION IDS
     * (`com.phairplay.firetv` / `com.phairplay.googletv`), so downloading the wrong one does not
     * fail loudly: Android sees a different package and installs it ALONGSIDE the running app
     * rather than updating it, leaving two PhairPlays on the device — or, on a Fire TV handed the
     * googletv build, refuses with INSTALL_FAILED_OLDER_SDK because that flavour is minSdk 29.
     * Picking "the first asset ending in .apk" made which of those happened depend on the order
     * assets were attached to the release.
     */
    suspend fun check(currentVersion: String, flavor: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetch(RELEASES_URL)
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifEmpty { return@runCatching Result.Failed("No tag on the latest release") }
            val notes = json.optString("body").take(NOTES_LIMIT)
            val apks = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            }.orEmpty()
            val match = apks.firstOrNull { it.optString("name").contains(flavor, ignoreCase = true) }
            // A single unnamed APK is taken as "this release only ships one build" and used. More
            // than one, none of them naming our flavour, is ambiguous -- and guessing here installs
            // a second copy of the app under the other package name, so say so instead.
            val asset = when {
                match != null -> match.optString("browser_download_url")
                apks.size == 1 -> apks[0].optString("browser_download_url")
                apks.isEmpty() -> null
                else -> return@runCatching Result.Failed(
                    "Release $tag has no $flavor build attached (found ${apks.size} other APKs)")
            }
            if (isNewer(tag, currentVersion)) Result.Available(tag, notes, asset) else Result.UpToDate(tag)
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Compares dotted versions numerically, ignoring a leading `v` and any build suffix.
     *
     * String comparison would call 1.10.0 older than 1.9.0, and the flavour suffix this app appends
     * (`1.0.0-firetv`) would make every comparison against a bare tag wrong.
     */
    private fun isNewer(tag: String, current: String): Boolean {
        val remote = parts(tag)
        val local = parts(current)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trimStart('v', 'V')
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    private fun fetch(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            try {
                // 404 here is the normal answer for a repo with no published releases, not a
                // broken URL -- the endpoint only exists once something has been released. Saying
                // "HTTP 404" to a user who has simply never cut a release is useless.
                if (responseCode == 404) error("No releases have been published yet")
                if (responseCode !in 200..299) error("GitHub answered HTTP $responseCode")
                inputStream.bufferedReader().readText()
            } finally {
                disconnect()
            }
        }

    private const val TIMEOUT_MS = 15_000

    /** Release notes are shown in a dialog on a TV, so a novel-length body helps nobody. */
    private const val NOTES_LIMIT = 600

    /**
     * Checks the rolling `dev` prerelease instead of the last tagged release.
     *
     * **Compared by COMMIT, not by version.** The dev tag is the literal string `dev` and its
     * `versionName` is whatever the last real release was, so both halves of the usual comparison
     * are constant — a numeric check would report "up to date" against every dev build ever
     * published. CI puts the commit SHA in the release name and this matches it against
     * [currentSha] (`BuildConfig.GIT_SHA`), which is the only thing that actually differs between
     * a device's build and the newest one.
     *
     * A missing SHA is reported as a failure rather than assumed up to date. That distinction is
     * the whole lesson of this file: an updater that cannot tell should say so, because a silent
     * "you are current" is indistinguishable from working and stays wrong forever.
     */
    suspend fun checkBeta(currentSha: String, flavor: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(fetch(DEV_RELEASE_URL))
            val name = json.optString("name")
            val remoteSha = SHA_PATTERN.find(name)?.value
                ?: return@runCatching Result.Failed(
                    "The dev release carries no build id — CI has to publish the commit in its name")
            val notes = json.optString("body").take(NOTES_LIMIT)
            val asset = pickAsset(json, flavor)
                ?: return@runCatching Result.Failed("The dev release has no $flavor build attached")

            // Prefix match in both directions: BuildConfig.GIT_SHA is short and the release name
            // carries the full hash, but a future change to either length should not silently
            // start reporting every build as new.
            val same = currentSha.isNotBlank() && currentSha != "unknown" &&
                (remoteSha.startsWith(currentSha) || currentSha.startsWith(remoteSha))
            val shortSha = remoteSha.take(7)
            if (same) Result.UpToDate("dev · $shortSha")
            else Result.Available("dev · $shortSha", notes, asset)
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** The APK for [flavor], with the same "one unnamed APK is fine, several ambiguous ones are not" rule. */
    private fun pickAsset(json: JSONObject, flavor: String): String? {
        val apks = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        }.orEmpty()
        val match = apks.firstOrNull { it.optString("name").contains(flavor, ignoreCase = true) }
        return when {
            match != null -> match.optString("browser_download_url")
            apks.size == 1 -> apks[0].optString("browser_download_url")
            else -> null
        }
    }
}
