package com.phairplay.media.shazam

import com.phairplay.util.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * ShazamClient — posts a fingerprint and reads back a track name.
 *
 * The request shape is taken from ShazamIO's `Converter.data_search` and `Request.headers`, not
 * invented. Two things about it are worth knowing before changing anything here:
 *
 * **`geolocation` and `context` are sent EMPTY.** The Rust core fills a geolocation in (altitude
 * 300, latitude 45, longitude 2 — a point in France) but the Python that actually issues the request
 * sends `{}`, and that is what is copied here. Nothing about where the television is leaves the
 * device, and nothing should be added that changes that.
 *
 * **This is not a public API.** There is no key, no documented contract and no promise it keeps
 * working. It can begin refusing unknown clients at any point, and when it does the failure will
 * look exactly like "this track is not in the database" — so every failure path here logs what
 * actually happened rather than collapsing to null silently.
 */
object ShazamClient {

    /** What a successful match yields. [artist] is Shazam's `subtitle`. */
    data class Match(val title: String, val artist: String?, val artworkUrl: String?)

    /**
     * Identifies a fingerprint.
     *
     * Blocking: call it off the packet thread. A lookup is a single round trip but crosses the
     * internet, and the caller that produced the samples is the one decoding audio.
     */
    fun identify(signature: ShazamSignature.Signature): Match? {
        val body = JSONObject().apply {
            put("timezone", timezone())
            put("signature", JSONObject().apply {
                put("uri", signature.toUri())
                put("samplems", signature.durationMs)
            })
            put("timestamp", System.currentTimeMillis())
            // Deliberately empty -- see the class comment. Present because the endpoint expects the
            // keys, not because there is anything to put in them.
            put("context", JSONObject())
            put("geolocation", JSONObject())
        }.toString()

        val url = buildString {
            append("https://amp.shazam.com/discovery/v5/")
            append(language()).append('/')
            append(country()).append('/')
            append("android/-/tag/")
            append(UUID.randomUUID().toString().uppercase()).append('/')
            append(UUID.randomUUID().toString().uppercase())
            append("?sync=true&webv3=true&sampling=true&connected=")
            append("&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3")
        }

        val response = post(url, body) ?: return null
        return parse(response)
    }

    private fun parse(json: String): Match? {
        val root = runCatching { JSONObject(json) }.getOrElse {
            Logger.i("Shazam: response was not JSON (${it.message})")
            return null
        }
        // `matches` empty is the ordinary "we do not know this" answer, and is not an error --
        // distinguishing it from a rejected request is the reason it is logged separately.
        val track = root.optJSONObject("track")
        if (track == null) {
            val matches = root.optJSONArray("matches")?.length() ?: 0
            val retry = root.optInt("retryms", 0)
            Logger.i("Shazam: no match (matches=$matches${if (retry > 0) ", retry in ${retry}ms" else ""})")
            return null
        }
        val title = track.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val artist = track.optString("subtitle").takeIf { it.isNotBlank() && it != "null" }
        val images = track.optJSONObject("images")
        val art = listOf("coverarthq", "coverart")
            .firstNotNullOfOrNull { images?.optString(it)?.takeIf { s -> s.isNotBlank() && s != "null" } }
        Logger.i("Shazam: matched \"$title\"${artist?.let { " — $it" } ?: ""}")
        return Match(title, artist, art)
    }

    private fun post(url: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", language())
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("X-Shazam-Platform", "ANDROID")
                setRequestProperty("X-Shazam-AppVersion", APP_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Read the error body: when the endpoint starts refusing clients this is the only
                // place that says so, and without it the refusal is indistinguishable from a miss.
                val detail = runCatching {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.take(200).orEmpty()
                Logger.i("Shazam: HTTP $code${if (detail.isNotBlank()) " — $detail" else ""}")
                return null
            }
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Logger.i("Shazam: request failed (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * The endpoint takes a language and a country in its path.
     *
     * Taken from the device's locale rather than pinned, because a match carries localised titles
     * and a hard-coded `en`/`GB` would hand a Japanese user romanised names for their own music.
     * Falls back rather than throwing on an unusual locale — an empty country segment 404s.
     */
    private fun language(): String = Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    private fun country(): String = Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "GB"

    /** ShazamIO pins `Europe/Moscow`; the device's own zone is both truer and no more identifying. */
    private fun timezone(): String =
        runCatching { java.util.TimeZone.getDefault().id }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Europe/London"

    private const val APP_VERSION = "14.1.0"
    private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 11; AFTKA Build/RS8117.2661N)"
    private const val TIMEOUT_MS = 10_000
}
