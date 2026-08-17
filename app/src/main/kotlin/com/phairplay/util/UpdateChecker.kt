package com.phairplay.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateChecker — asks GitHub whether a newer release exists, and installs it.
 *
 * A Fire TV has no browser worth the name, so "here is a link to the release page" would be a dead
 * end. Instead the whole path lives here: read the latest release, compare versions, download the
 * APK asset, and hand it to the package installer.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO: install anything on its own. [download] returns an Intent the
 * user has to confirm on the system's own installer screen, and no check runs unless the user opens
 * the settings row. Silent background self-updating is not a thing a sideloaded receiver app should
 * be doing to someone's television.
 */
object UpdateChecker {

    /** The repo releases are published from — the user's fork, not the upstream one. */
    private const val RELEASES_URL = "https://api.github.com/repos/JObersi10/PhairPlay/releases/latest"

    sealed interface Result {
        /** A newer release exists. [tag] is what to show; [assetUrl] may be null if none was attached. */
        data class Available(val tag: String, val notes: String, val assetUrl: String?) : Result
        data class UpToDate(val tag: String) : Result
        /** Anything went wrong. [reason] is shown to the user rather than swallowed. */
        data class Failed(val reason: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetch(RELEASES_URL)
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifEmpty { return@runCatching Result.Failed("No tag on the latest release") }
            val notes = json.optString("body").take(NOTES_LIMIT)
            val asset = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
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

    /**
     * Downloads [url] and returns an install Intent for it.
     *
     * @return null if the download failed. The caller says so rather than opening an installer for a
     *   half-written file.
     */
    suspend fun download(context: Context, url: String): Intent? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(context.cacheDir, "update.apk")
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrElse {
            Logger.w("Update download failed — ${it.message}")
            null
        }
    }

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
}
