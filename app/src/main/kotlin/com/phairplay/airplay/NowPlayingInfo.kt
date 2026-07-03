package com.phairplay.airplay

/**
 * NowPlayingInfo — what the audio-only "now playing" screen shows while audio (and no video) is
 * streaming. [senderName] is always present; the track metadata and [artwork] are filled in only
 * when the sender pushes them (Apple Music / Podcasts do; raw system audio from Chrome does not, so
 * the screen falls back to a generic "Audio from <sender>" card).
 */
data class NowPlayingInfo(
    val senderName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val year: Int? = null,
    val artwork: ByteArray? = null,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
) {
    /** True when the sender supplied at least a track title (vs. a bare "audio is playing" state). */
    val hasMetadata: Boolean get() = !title.isNullOrBlank()

    // ByteArray needs hand-written equals/hashCode for value semantics in a StateFlow.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingInfo) return false
        return senderName == other.senderName &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            genre == other.genre &&
            composer == other.composer &&
            year == other.year &&
            artwork.contentEquals(other.artwork) &&
            positionSec == other.positionSec &&
            durationSec == other.durationSec
    }

    override fun hashCode(): Int {
        var result = senderName.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        result = 31 * result + positionSec.hashCode()
        result = 31 * result + durationSec.hashCode()
        return result
    }
}

/**
 * DmapParser — minimal reader for the DMAP/DAAP "now playing" metadata macOS sends in a binary
 * `SET_PARAMETER` body (`Content-Type: application/x-dmap-tagged`).
 *
 * DMAP is a flat TLV stream: a 4-byte ASCII tag, a 4-byte big-endian length, then that many payload
 * bytes. Container tags (e.g. `mlit`, a listing item) hold nested tags. We only need three string
 * fields — track ([minm]), artist ([asar]), album ([asal]) — so we walk the tree and pick them out
 * wherever they appear, ignoring everything else.
 *
 * Reference: DAAP/DMAP content codes (minm/asar/asal) used by AirPlay now-playing.
 */
object DmapParser {

    private val CONTAINER_TAGS = setOf("mlit", "mlcl", "mshl", "adbs")
    private const val TRACK_TAG    = "minm"  // track name
    private const val ARTIST_TAG   = "asar"  // artist
    private const val ALBUM_TAG    = "asal"  // album
    private const val SONGTIME_TAG = "astm"  // duration in ms (4-byte int)
    private const val GENRE_TAG    = "asgn"  // genre string
    private const val COMPOSER_TAG = "ascp"  // composer string
    private const val YEAR_TAG     = "asyr"  // year (2-byte int)

    data class Metadata(
        val title: String?, val artist: String?, val album: String?,
        val genre: String?, val composer: String?, val year: Int?,
        val durationMs: Long?,
    ) {
        val isEmpty: Boolean get() = title == null && artist == null && album == null
    }

    fun parseNowPlaying(body: ByteArray): Metadata {
        val acc = MutableMetadata()
        runCatching { walk(body, 0, body.size, acc, depth = 0) }
        return Metadata(acc.title, acc.artist, acc.album, acc.genre, acc.composer, acc.year, acc.durationMs)
    }

    private class MutableMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var composer: String? = null
        var year: Int? = null
        var durationMs: Long? = null
    }

    private fun walk(buf: ByteArray, start: Int, end: Int, acc: MutableMetadata, depth: Int) {
        if (depth > MAX_DEPTH) return
        var i = start
        while (i + 8 <= end) {
            val tag = String(buf, i, 4, Charsets.US_ASCII)
            val len = readBE32(buf, i + 4)
            val payloadStart = i + 8
            if (len < 0 || payloadStart + len > end) break
            when (tag) {
                TRACK_TAG    -> if (acc.title == null)    acc.title    = utf8(buf, payloadStart, len)
                ARTIST_TAG   -> if (acc.artist == null)   acc.artist   = utf8(buf, payloadStart, len)
                ALBUM_TAG    -> if (acc.album == null)    acc.album    = utf8(buf, payloadStart, len)
                GENRE_TAG    -> if (acc.genre == null)    acc.genre    = utf8(buf, payloadStart, len)
                COMPOSER_TAG -> if (acc.composer == null) acc.composer = utf8(buf, payloadStart, len)
                YEAR_TAG     -> if (acc.year == null && len >= 2) acc.year = readBE16(buf, payloadStart)
                SONGTIME_TAG -> if (acc.durationMs == null && len >= 4) acc.durationMs = readBE32(buf, payloadStart).toLong() and 0xFFFFFFFFL
                in CONTAINER_TAGS -> walk(buf, payloadStart, payloadStart + len, acc, depth + 1)
                else -> { /* skip */ }
            }
            i = payloadStart + len
        }
    }

    private fun utf8(buf: ByteArray, start: Int, len: Int): String? =
        String(buf, start, len, Charsets.UTF_8).trim().ifEmpty { null }

    private fun readBE32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
        ((buf[off + 1].toInt() and 0xFF) shl 16) or
        ((buf[off + 2].toInt() and 0xFF) shl 8) or
        (buf[off + 3].toInt() and 0xFF)

    private fun readBE16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private const val MAX_DEPTH = 8
}
