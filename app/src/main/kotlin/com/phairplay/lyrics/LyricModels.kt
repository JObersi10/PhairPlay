package com.phairplay.lyrics

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

data class LyricBackground(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val background: LyricBackground? = null
)
