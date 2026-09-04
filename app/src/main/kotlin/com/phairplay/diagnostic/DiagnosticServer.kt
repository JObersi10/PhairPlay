package com.phairplay.diagnostic

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ServerSocket

object DiagnosticServer {
    const val PORT = 8001
    @Volatile private var started = false
    private var dumpSocket: ServerSocket? = null
    private var tailSocket: ServerSocket? = null

    /**
     * Current state to print above the log, set by the service.
     *
     * The ring buffer only holds events since the app started and it is small, so anything
     * established once at startup -- the build, the output being played to -- has usually scrolled
     * away by the time there is a problem worth dumping. Those are exactly the two facts you want
     * first when reading someone else's dump, so they are re-derived on every request instead of
     * being left to survive in the buffer.
     */
    @Volatile var statusProvider: (() -> String)? = null

    fun stop() {
        started = false
        dumpSocket?.runCatching { close() }; dumpSocket = null
        tailSocket?.runCatching { close() }; tailSocket = null
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        // ONE PORT, TWO PATHS. This used to bind 8001 for the dump and 8002 for the live tail, which
        // is two listening sockets and two things to remember for one feature. They are the same
        // resource viewed two ways, so they are the same port now and the request path chooses:
        //   GET /       the full dump, then close
        //   GET /tail   the same buffer, streamed, held open
        scope.launch(Dispatchers.IO) {
            runCatching {
                val server = ServerSocket(PORT).also { dumpSocket = it }
                Logger.i("DiagnosticServer on :$PORT  (/ = dump, /tail = live)")
                while (true) {
                    val client = server.accept()
                    launch(Dispatchers.IO) {
                        runCatching {
                            // Read only the request line. Nothing here needs headers, and reading to
                            // the blank line would block on a client that never sends one.
                            val line = StringBuilder()
                            val input = client.getInputStream()
                            while (line.length < 512) {
                                val c = input.read()
                                if (c < 0 || c == '\n'.code) break
                                if (c != '\r'.code) line.append(c.toChar())
                            }
                            val path = line.toString().split(' ').getOrNull(1).orEmpty()
                            if (path.startsWith("/tail")) streamTail(client) else writeDump(client)
                        }.onFailure { client.runCatching { close() } }
                    }
                }
            // stop() clears `started` before closing the socket, so a throw while stopped is the
            // expected accept() interruption — logging it at ERROR flooded the ring buffer with
            // stack traces on every clean shutdown.
            }.onFailure { if (started) Logger.e("DiagnosticServer error", it) }
        }
    }

    /** The whole buffer, led by the status header, then the connection closes. */
    private fun writeDump(client: java.net.Socket) {
        val out = client.getOutputStream()
        val status = runCatching { statusProvider?.invoke() }.getOrNull()
        val body = (status?.let { "$it\n\n" }.orEmpty() + LogBuffer.dump()).toByteArray()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
             "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
        client.close()
    }

    /** The same buffer, chunked and held open, so new lines arrive as they are written. */
    private fun streamTail(client: java.net.Socket) {
        val out = client.getOutputStream()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
             "Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n").toByteArray()
        )
        out.flush()
        var cursor = 0
        while (true) {
            val (lines, newSize) = LogBuffer.dumpFrom(cursor)
            if (lines.isNotEmpty()) {
                val bytes = (lines.joinToString("\n") + "\n").toByteArray()
                out.write("${bytes.size.toString(16)}\r\n".toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())
                out.flush()
                cursor = newSize
            }
            Thread.sleep(100)
        }
    }
}
