package com.phairplay.lyrics

import com.phairplay.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LyricsRepository {

    private const val UA = "PhairPlay (github.com/mazer666/PhairPlay)"
    private const val TIMEOUT_MS = 10_000

    suspend fun fetch(
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Double = 0.0
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            Logger.i("LyricsRepository: fetching '$title' by '$artist'")
            // Step 1: exact match
            val exact = getExact(title, artist, album, durationSec)
            if (exact != null) return@withContext exact

            // Step 2: search fallback
            getFromSearch(title, artist, durationSec) ?: emptyList()
        }.onFailure {
            Logger.e("LyricsRepository: fetch failed for '$title'", it)
        }.getOrDefault(emptyList())
    }

    private fun getExact(title: String, artist: String, album: String?, durationSec: Double): List<LyricLine>? {
        val params = buildString {
            append("track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (durationSec > 0) append("&duration=").append(durationSec.toInt())
        }
        val json = get("https://lrclib.net/api/get?$params") ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!isGoodMatch(obj, artist, durationSec)) return null
        val lrc = obj.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: return null
        return LrcParser.parse(lrc).takeIf { it.isNotEmpty() }
    }

    private fun getFromSearch(title: String, artist: String, durationSec: Double): List<LyricLine>? {
        val params = "track_name=${enc(title)}&artist_name=${enc(artist)}"
        val json = get("https://lrclib.net/api/search?$params") ?: return null
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (!isGoodMatch(obj, artist, durationSec)) continue
            val lrc = obj.optString("syncedLyrics").takeIf { it.isNotBlank() } ?: continue
            val lines = LrcParser.parse(lrc)
            if (lines.isNotEmpty()) return lines
        }
        Logger.w("LyricsRepository: search returned ${arr.length()} results but none matched for '$title'")
        return null
    }

    private fun isGoodMatch(data: JSONObject, artist: String, durationSec: Double): Boolean {
        val got  = data.optString("artistName").lowercase().replace(Regex("[^a-z0-9]"), "")
        val want = artist.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (got.isNotEmpty() && want.isNotEmpty() && !got.contains(want) && !want.contains(got)) return false
        if (durationSec > 0 && data.has("duration")) {
            val diff = Math.abs(data.optDouble("duration") - durationSec)
            if (diff > 10) return false
        }
        return true
    }

    private fun get(urlStr: String): String? {
        return runCatching {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val code = conn.responseCode
            if (code != 200) { Logger.w("lrclib $code for $urlStr"); return null }
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        }.onFailure { Logger.e("lrclib GET failed: $urlStr", it) }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
