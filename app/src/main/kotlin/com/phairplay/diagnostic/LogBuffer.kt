package com.phairplay.diagnostic

import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogBuffer {
    private const val MAX = 500
    private const val FILE_MAX_LINES = 1000
    private val buf = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var fileLineCount = 0
    private var previousSession: String = ""

    fun init(filesDir: File) {
        val f = File(filesDir, "phairplay.log")
        if (f.exists()) {
            val lines = f.readLines()
            previousSession = last5Seconds(lines)
            f.delete()
            fileLineCount = 0
        }
        logFile = f
    }

    private fun last5Seconds(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val lastTs = parseTs(lines.last()) ?: return lines.takeLast(50).joinToString("\n")
        val cutoff = lastTs - 5000L
        return lines.filter { line ->
            val ts = parseTs(line)
            ts == null || ts >= cutoff
        }.joinToString("\n")
    }

    private fun parseTs(line: String): Long? {
        if (line.length < 12) return null
        return runCatching {
            val parts = line.substring(0, 12).split(":", ".")
            if (parts.size < 4) return null
            val h = parts[0].toLong(); val m = parts[1].toLong()
            val s = parts[2].toLong(); val ms = parts[3].toLong()
            h * 3600000 + m * 60000 + s * 1000 + ms
        }.getOrNull()
    }

    fun add(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(buf) {
            if (buf.size >= MAX) buf.removeAt(0)
            buf.add(line)
        }
        logFile?.let { f ->
            try {
                f.appendText(line + "\n")
                fileLineCount++
                if (fileLineCount > FILE_MAX_LINES) {
                    val trimmed = f.readLines().takeLast(FILE_MAX_LINES)
                    f.writeText(trimmed.joinToString("\n") + "\n")
                    fileLineCount = trimmed.size
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    fun dump(): String {
        val current = synchronized(buf) { buf.joinToString("\n") }
        return if (previousSession.isNotEmpty())
            "---- PREVIOUS SESSION ----\n$previousSession\n---- CURRENT SESSION ----\n$current"
        else current
    }

    fun size(): Int = synchronized(buf) { buf.size }

    fun dumpFrom(fromIndex: Int): Pair<List<String>, Int> = synchronized(buf) {
        val lines = if (fromIndex < buf.size) buf.subList(fromIndex, buf.size).toList() else emptyList()
        Pair(lines, buf.size)
    }

    class Tree : Timber.Tree() {
        private val levels = mapOf(2 to "V", 3 to "D", 4 to "I", 5 to "W", 6 to "E")
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val line = "[${levels[priority] ?: "?"}/${tag ?: "?"}] $message" +
                (t?.let { " | ${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString()}" } ?: "")
            add(line)
        }
    }
}
