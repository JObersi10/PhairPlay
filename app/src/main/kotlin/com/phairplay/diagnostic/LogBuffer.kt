package com.phairplay.diagnostic

import timber.log.Timber
object LogBuffer {
    private const val MAX = 500
    private val buf = mutableListOf<String>()

    fun add(msg: String) = synchronized(buf) {
        if (buf.size >= MAX) buf.removeAt(0)
        buf.add(msg)
    }

    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }

    fun size(): Int = synchronized(buf) { buf.size }

    /** Returns lines from [fromIndex] onward and the new total size. */
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
