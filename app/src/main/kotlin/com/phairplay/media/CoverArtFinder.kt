package com.phairplay.media

import com.phairplay.util.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CoverArtFinder — finds album art for a track that arrived without any.
 *
 * WHY THIS EXISTS: AirPlay senders push artwork as part of the stream, so the now-playing card
 * always has an image. DLNA control points mostly do not: the DIDL-Lite document may carry an
 * `upnp:albumArtURI`, but plenty of servers omit it and send a bare title, which left the card with
 * a blank square for the whole render.
 *
 * SERVICE CHOICE: MusicBrainz for the lookup and the Cover Art Archive for the image. The deciding
 * factor is that neither needs an API key or an account — the alternatives (Last.fm, Spotify,
 * Discogs, TheAudioDB) all require registering credentials, which would mean either shipping a key
 * in the APK or asking the user to paste one into a TV settings screen. Both are also run by the
 * MetaBrainz Foundation, so the release MBID that comes out of the first request is exactly what the
 * second request takes, with no ID mapping in between.
 *
 * MusicBrainz requires a descriptive User-Agent and rate-limits anonymous callers to roughly one
 * request per second. Both are honoured below; a receiver looks something up once per track change,
 * so the limit is never close.
 */
object CoverArtFinder {

    /**
     * Where a cover came from, for the log. A blank card and a card whose lookup found nothing look
     * identical on screen and have completely different causes.
     */
    enum class Source { DIDL, LOOKUP, NONE }

    data class Result(val bytes: ByteArray?, val source: Source)

    /**
     * Fetches artwork, preferring what the sender supplied.
     *
     * @param didlUri artwork URL from the DIDL-Lite document, if the control point sent one.
     * @param title track title. Used only as a fallback query when there is no album.
     * @param lookupEnabled false leaves this at the DIDL URI alone and never touches the network for
     *   a lookup — the user's setting, since a receiver phoning out to a third party is a choice they
     *   should get to make rather than a default.
     */
    fun find(
        didlUri: String?,
        title: String?,
        artist: String?,
        album: String?,
        lookupEnabled: Boolean,
    ): Result {
        if (!didlUri.isNullOrBlank()) {
            val bytes = fetch(didlUri)
            if (bytes != null) return Result(bytes, Source.DIDL)
            Logger.i("Cover art: DIDL URI gave nothing ($didlUri)")
        }
        if (!lookupEnabled) return Result(null, Source.NONE)

        val mbid = searchRelease(artist, album, title) ?: return Result(null, Source.NONE)
        val bytes = fetch("$COVER_ART_ARCHIVE/release/$mbid/front-500")
        return if (bytes != null) Result(bytes, Source.LOOKUP) else Result(null, Source.NONE)
    }

    /**
     * Asks MusicBrainz for the most likely release MBID.
     *
     * Queried as fielded Lucene terms rather than as one free-text string: `release:"X" AND
     * artist:"Y"` is dramatically more precise than the same words thrown at the default field, which
     * happily matches a compilation that merely mentions them. Album is preferred over title because
     * the Cover Art Archive is indexed by RELEASE — a track title finds the right release only when
     * it happens to also be the album name, so it is the last resort, not the first choice.
     */
    private fun searchRelease(artist: String?, album: String?, title: String?): String? {
        val terms = buildList {
            val release = album?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }
            if (release != null) add("release:${quote(release)}")
            if (!artist.isNullOrBlank()) add("artist:${quote(artist)}")
        }
        if (terms.isEmpty()) return null

        val query = URLEncoder.encode(terms.joinToString(" AND "), "UTF-8")
        val url = "$MUSICBRAINZ/release?query=$query&limit=1&fmt=json"
        val body = fetchText(url) ?: return null

        // Deliberately not a JSON parse. The one value needed is the first "id" inside the first
        // release object, and pulling in a parser (or hand-rolling one) to reach it would be more
        // code and more failure surface than locating a 36-character UUID.
        val mbid = MBID_REGEX.find(body)?.value
        if (mbid == null) Logger.i("Cover art: no MusicBrainz match for ${terms.joinToString(" AND ")}")
        return mbid
    }

    /** Lucene needs the inner quotes escaped, or a title containing one breaks the whole query. */
    private fun quote(s: String) = "\"" + s.replace("\\", "").replace("\"", "\\\"") + "\""

    private fun fetchText(url: String): String? = openStream(url) { it.readBytes().toString(Charsets.UTF_8) }

    private fun fetch(url: String): ByteArray? = openStream(url) { stream ->
        // Bounded read. This URL can come from a DIDL document written by another device on the
        // network, so its size is not ours to trust; an unbounded read would let a hostile or simply
        // broken server hand a TV enough bytes to run it out of memory.
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) {
                Logger.w("Cover art: image over ${MAX_IMAGE_BYTES / 1024}KB — abandoned")
                return@openStream null
            }
            out.write(buf, 0, n)
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun <T> openStream(url: String, read: (java.io.InputStream) -> T?): T? {
        var conn: HttpURLConnection? = null
        return try {
            // followRedirects matters here rather than being boilerplate: the Cover Art Archive
            // answers every image request with a 307 to its storage host, so without it every
            // lookup returns a redirect body and no picture.
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // MusicBrainz blocks anonymous callers that do not identify themselves, and returns
                // 403 rather than an empty result — which would otherwise look exactly like "this
                // album is not in the database".
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.i("Cover art: HTTP $code for $url")
                return null
            }
            conn.inputStream.use(read)
        } catch (e: Exception) {
            Logger.i("Cover art: request failed (${e.javaClass.simpleName}: ${e.message})")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2"
    private const val COVER_ART_ARCHIVE = "https://coverartarchive.org"

    /**
     * MusicBrainz asks for application, version and a contact address. A generic agent is what gets
     * rate-limited hardest, so identifying properly is in our interest.
     */
    private const val USER_AGENT = "PhairPlay/1.0 ( https://github.com/JObersi10/PhairPlay )"

    private const val TIMEOUT_MS = 8000
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    private val MBID_REGEX =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
