package com.phairplay.homekit

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * HapServer — the accessory's HTTP endpoint.
 *
 * WHY it is not just an HTTP server: HAP starts as plain HTTP over TCP, and the moment pair-verify
 * completes EVERY byte in both directions becomes ChaCha20-Poly1305 records. The switch happens
 * *after* the M4 response is written in the clear, so the transition point is exact and easy to get
 * wrong by one message in either direction.
 *
 * Each connection owns its own [HapPairing] (state is per-connection) and its own nonce counters,
 * which start at zero for each direction and never reset for the life of the connection.
 *
 * Events are the other unusual part: the accessory pushes unsolicited `EVENT/1.0 200 OK` messages
 * on the same socket, framed identically to responses. That is how the Home app sees the TV turn
 * on when someone uses the physical remote.
 */
class HapServer(
    private val store: HapStore,
    private val accessories: List<HapAccessory>,
    private val onIdentify: () -> Unit,
    private val onPairedChanged: () -> Unit,
) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val connections = CopyOnWriteArrayList<Connection>()

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        val ss = ServerSocket(0)
        serverSocket = ss
        running = true
        thread(name = "hap-accept", isDaemon = true) {
            while (running) {
                val socket = runCatching { ss.accept() }.getOrNull() ?: break
                val conn = Connection(socket)
                connections += conn
                thread(name = "hap-conn", isDaemon = true) {
                    runCatching { conn.run() }
                        .onFailure { if (running) Logger.w("HAP connection error: ${it.message}") }
                    connections -= conn
                    runCatching { socket.close() }
                }
            }
        }
        Logger.i("HAP server listening on port ${ss.localPort}")
        return ss.localPort
    }

    fun stop() {
        running = false
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Logger.i("HAP server stopped")
    }

    /**
     * Pushes a characteristic change to every controller subscribed to it.
     *
     * Only subscribed characteristics may be sent: an unsolicited event for something the
     * controller never asked about makes iOS drop the connection.
     */
    fun notifyChanged(aid: Int, characteristic: HapCharacteristic) {
        if (!characteristic.subscribed) return
        val body = "{\"characteristics\":[${characteristic.toJson(aid, includeMeta = false)}]}"
        val message = buildString {
            append("EVENT/1.0 200 OK\r\n")
            append("Content-Type: application/hap+json\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(body)
        }.toByteArray(Charsets.UTF_8)
        connections.forEach { runCatching { it.sendRaw(message) } }
    }

    private inner class Connection(private val socket: Socket) {

        private val pairing = HapPairing(
            store = store,
            accessoryId = store.accessoryId,
            setupCode = { store.setupCode },
            onPairedChanged = onPairedChanged,
        )

        private var readCounter = 0L
        private var writeCounter = 0L
        private lateinit var input: InputStream
        private lateinit var output: OutputStream

        /** Non-null once pair-verify completed; presence of this IS "the session is encrypted". */
        private val session get() = pairing.session

        fun close() = runCatching { socket.close() }.let { }

        fun run() {
            socket.tcpNoDelay = true
            input = socket.getInputStream().buffered()
            output = socket.getOutputStream()
            while (running && !socket.isClosed) {
                val request = readRequest() ?: break
                handle(request)
            }
        }

        /**
         * Bytes received but not yet consumed by a parsed request.
         *
         * PERSISTS ACROSS REQUESTS, and that is the whole point. This used to be a fresh buffer per
         * call, so anything a single read() pulled in beyond the first complete request was silently
         * dropped. iOS pipelines pair-setup M5 straight down the same connection behind M3, so M5
         * routinely arrived in the same TCP segment as M3 and was thrown away: the accessory sent
         * M4, went quiet, and the phone eventually gave up with "this accessory cannot be used with
         * HomeKit" -- a message that says nothing about a discarded buffer.
         */
        private var pending = ByteArray(0)

        /**
         * Reads one HTTP request, transparently decrypting when the session is up.
         *
         * Encrypted mode has to reassemble: a single request may span several 1024-byte records,
         * and the HTTP headers that say how long the body is only appear after the first record is
         * decrypted. So we decrypt one record at a time and re-check whether a full request has
         * arrived, rather than trying to size the read up front.
         */
        private fun readRequest(): Request? {
            while (true) {
                // Parse BEFORE reading: the previous call may already have left a whole request
                // behind, and blocking on read() first would deadlock waiting for bytes the
                // controller has no reason to send.
                parseRequest(pending)?.let { (request, consumed) ->
                    pending = pending.copyOfRange(consumed, pending.size)
                    return request
                }
                if (session == null) {
                    val chunk = ByteArray(4096)
                    val n = input.read(chunk)
                    if (n <= 0) return null
                    pending += chunk.copyOfRange(0, n)
                } else {
                    pending += (readEncryptedRecord() ?: return null)
                }
            }
        }

        private fun readEncryptedRecord(): ByteArray? {
            val key = session?.readKey ?: return null
            val lengthBytes = readExactly(2) ?: return null
            val length = (lengthBytes[0].toInt() and 0xFF) or ((lengthBytes[1].toInt() and 0xFF) shl 8)
            val payload = readExactly(length + HapCrypto.TAG_BYTES) ?: return null
            return runCatching {
                HapCrypto.open(key, HapCrypto.sessionNonce(readCounter), payload, lengthBytes)
            }.getOrElse {
                Logger.w("HAP record decrypt failed — dropping connection: ${it.message}")
                null
            }?.also { readCounter++ }
        }

        private fun readExactly(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r <= 0) return null
                off += r
            }
            return buf
        }

        /**
         * Returns the request AND how many bytes it consumed, or null when the buffer does not yet
         * hold a complete request.
         *
         * The byte count is what lets the caller keep the remainder instead of discarding it.
         */
        private fun parseRequest(data: ByteArray): Pair<Request, Int>? {
            val headerEnd = indexOfDoubleCrlf(data) ?: return null
            val headerText = String(data, 0, headerEnd, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 2) return null

            val contentLength = lines.drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

            val bodyStart = headerEnd + 4
            if (data.size < bodyStart + contentLength) return null    // body still arriving

            val request = Request(
                method = requestLine[0],
                target = requestLine[1],
                body = data.copyOfRange(bodyStart, bodyStart + contentLength),
            )
            return request to (bodyStart + contentLength)
        }

        private fun indexOfDoubleCrlf(data: ByteArray): Int? {
            for (i in 0..data.size - 4) {
                if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) return i
            }
            return null
        }

        private fun handle(request: Request) {
            val path = request.target.substringBefore('?')
            when {
                path == "/pair-setup" -> {
                    respondTlv(pairing.pairSetup(request.body))
                }
                path == "/pair-verify" -> {
                    // The M4 reply itself goes out in the clear; only what follows is encrypted.
                    // Capturing the session before writing would encrypt this response and strand
                    // the controller, which cannot yet decrypt.
                    val before = pairing.session
                    val reply = pairing.pairVerify(request.body)
                    respondTlv(reply, forcePlain = before == null)
                }
                // Everything below requires a verified session. Answering any of it unverified
                // would expose the accessory's state and controls to the whole network.
                session == null -> respondStatus(470, "Connection Authorization Required")

                path == "/pairings" -> respondTlv(pairing.pairings(request.body))
                path == "/accessories" -> respondJson(HapAccessory.database(accessories))
                path == "/characteristics" && request.method == "GET" ->
                    respondJson(readCharacteristics(request.target))
                path == "/characteristics" && request.method == "PUT" ->
                    writeCharacteristics(request.body)
                path == "/identify" -> { onIdentify(); respondStatus(204, "No Content") }
                else -> respondStatus(404, "Not Found")
            }
        }

        /** `GET /characteristics?id=1.11,1.12` → the current values. */
        private fun readCharacteristics(target: String): String {
            val ids = target.substringAfter("id=", "").substringBefore('&')
            val parts = ids.split(',').mapNotNull { spec ->
                val (aidStr, iidStr) = spec.split('.').let {
                    if (it.size == 2) it[0] to it[1] else return@mapNotNull null
                }
                val aid = aidStr.toIntOrNull() ?: return@mapNotNull null
                val iid = iidStr.toIntOrNull() ?: return@mapNotNull null
                accessories.firstOrNull { it.aid == aid }?.characteristic(iid)?.toJson(aid, includeMeta = false)
            }
            return "{\"characteristics\":[${parts.joinToString(",")}]}"
        }

        /**
         * `PUT /characteristics` — writes values and/or changes event subscriptions.
         *
         * Parsed by hand rather than with a JSON library: the body shape is fixed and tiny, and
         * this runs on every remote key press, so it stays dependency-free and allocation-light.
         */
        private fun writeCharacteristics(body: ByteArray) {
            val text = String(body, Charsets.UTF_8)
            var applied = 0
            for (entry in splitObjects(text)) {
                val aid = jsonInt(entry, "aid") ?: continue
                val iid = jsonInt(entry, "iid") ?: continue
                val ch = accessories.firstOrNull { it.aid == aid }?.characteristic(iid) ?: continue

                jsonBool(entry, "ev")?.let { ch.subscribed = it }

                val rawValue = jsonRaw(entry, "value")
                if (rawValue != null) {
                    val parsed: Any = when {
                        rawValue.startsWith("\"") -> rawValue.trim('"')
                        rawValue == "true" -> true
                        rawValue == "false" -> false
                        else -> rawValue.toIntOrNull() ?: rawValue
                    }
                    if (ch.writable) {
                        ch.value = parsed
                        runCatching { ch.onWrite?.invoke(parsed) }
                            .onFailure { Logger.w("HAP write handler failed for iid=$iid: ${it.message}") }
                        applied++
                    }
                }
            }
            if (applied > 0) Logger.i("HAP applied $applied characteristic write(s)")
            respondStatus(204, "No Content")
        }

        // ─── Response writing ────────────────────────────────────────────────

        private fun respondTlv(body: ByteArray, forcePlain: Boolean = false) =
            respond("200 OK", "application/pairing+tlv8", body, forcePlain)

        private fun respondJson(json: String) =
            respond("200 OK", "application/hap+json", json.toByteArray(Charsets.UTF_8), false)

        private fun respondStatus(code: Int, reason: String) =
            respond("$code $reason", null, ByteArray(0), false)

        private fun respond(status: String, contentType: String?, body: ByteArray, forcePlain: Boolean) {
            val head = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                append("Content-Length: ").append(body.size).append("\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            sendRaw(head + body, forcePlain)
        }

        fun sendRaw(data: ByteArray, forcePlain: Boolean = false) {
            synchronized(output) {
                val key = session?.writeKey
                if (key == null || forcePlain) {
                    output.write(data)
                } else {
                    val (frames, next) = HapCrypto.encodeFrames(key, writeCounter, data)
                    writeCounter = next
                    output.write(frames)
                }
                output.flush()
            }
        }

        private inner class RequestHolder
    }

    private class Request(val method: String, val target: String, val body: ByteArray)

    companion object {
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        /** Splits the `characteristics` array into top-level `{...}` objects. */
        internal fun splitObjects(text: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var start = -1
            for (i in text.indices) {
                when (text[i]) {
                    '{' -> { if (depth == 0) start = i; depth++ }
                    '}' -> { depth--; if (depth == 0 && start >= 0) { out += text.substring(start, i + 1); start = -1 } }
                }
            }
            // The outermost object is the wrapper; drop it when it contains the others.
            return if (out.size > 1) out.drop(1) else out
        }

        internal fun jsonRaw(obj: String, key: String): String? {
            val marker = "\"$key\""
            val at = obj.indexOf(marker).takeIf { it >= 0 } ?: return null
            var i = obj.indexOf(':', at + marker.length).takeIf { it >= 0 } ?: return null
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            return if (obj[i] == '"') {
                val end = obj.indexOf('"', i + 1).takeIf { it > 0 } ?: return null
                obj.substring(i, end + 1)
            } else {
                val end = generateSequence(i) { it + 1 }
                    .first { it >= obj.length || obj[it] == ',' || obj[it] == '}' }
                obj.substring(i, end).trim()
            }
        }

        internal fun jsonInt(obj: String, key: String): Int? = jsonRaw(obj, key)?.trim('"')?.toIntOrNull()

        internal fun jsonBool(obj: String, key: String): Boolean? = when (jsonRaw(obj, key)?.trim('"')) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }
}
