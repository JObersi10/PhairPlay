package com.phairplay.lyrics

object LrcParser {

    private val TIMESTAMP_RE = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    fun parse(lrc: String): List<LyricLine> {
        val entries = mutableListOf<Pair<Long, String>>()

        for (raw in lrc.lines()) {
            val timestamps = TIMESTAMP_RE.findAll(raw).toList()
            if (timestamps.isEmpty()) continue

            val text = TIMESTAMP_RE.replace(raw, "").trim()
            if (text.isEmpty()) continue

            for (m in timestamps) {
                val min  = m.groupValues[1].toLongOrNull() ?: continue
                val sec  = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3].let { f ->
                    if (f.isEmpty()) 0L
                    else f.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                }
                entries += Pair(min * 60_000L + sec * 1_000L + frac, text)
            }
        }

        entries.sortBy { it.first }

        return entries.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < entries.size) entries[i + 1].first else startMs + 5_000L
            LyricLine(startMs = startMs, endMs = endMs, text = text)
        }
    }
}
