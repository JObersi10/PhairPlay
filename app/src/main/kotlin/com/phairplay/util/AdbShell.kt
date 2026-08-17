package com.phairplay.util

import android.content.Context
import java.io.DataInputStream
import java.io.PushbackInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * AdbShell — runs shell commands by speaking the adb protocol to the device's own adb daemon.
 *
 * WHY: `adb shell input keyevent` drives the real input pipeline, so it works in every app --
 * the Fire TV launcher, Netflix, games -- where the accessibility service's focus traversal does
 * not. It works because the shell UID holds `INJECT_EVENTS`, which is signature|privileged and
 * unreachable for us. We cannot BE the shell, but we can ASK it: adbd listens on TCP 5555 when
 * "ADB debugging" is on, and it does not care that the client is on the same device.
 *
 * WHAT THE USER HAS TO DO, ONCE:
 *  1. Settings → My Fire TV → Developer options → ADB debugging → On. This survives reboots.
 *  2. Accept the "Allow debugging?" dialog the first time we connect, ticking **Always allow**.
 *     That writes our public key to /data/misc/adb/adb_keys and also survives reboots.
 *
 * After that it is permanent and invisible. That property is the whole reason this exists rather
 * than a Shizuku client: Shizuku has to be restarted over adb after every single reboot.
 *
 * PROTOCOL: messages are a 24-byte little-endian header (command, arg0, arg1, length, checksum,
 * magic = command xor 0xFFFFFFFF) followed by the payload. We CNXN, answer the AUTH challenge by
 * signing the token with our RSA key (or offering the public key, which raises the dialog), then
 * OPEN one `shell:` stream per command.
 *
 * Reference: platform/system/core/adb/protocol.txt.
 */
object AdbShell {

    // ── Connection state ─────────────────────────────────────────────────────
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var pushback: PushbackInputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1

    /** Set once we have a working connection, so key presses do not re-probe on every press. */
    @Volatile private var connected = false

    /**
     * Why the last attempt failed, for the settings screen to show.
     *
     * Null while things are fine. This is deliberately surfaced rather than swallowed: "the remote
     * does nothing" with no explanation is the single most useless failure mode this app has.
     */
    @Volatile var lastError: String? = null
        private set

    /** True when a live adb connection exists. */
    val isConnected: Boolean get() = connected

    /**
     * Sends [keyCode] through the real input pipeline.
     *
     * @return false if adb is unreachable or unauthorised, so the caller falls back to the
     *   accessibility service rather than silently dropping the press.
     */
    @Synchronized
    fun sendKeyEvent(context: Context, keyCode: Int): Boolean =
        exec(context, "input keyevent $keyCode")

    /** Runs [command] in a shell. @return false on any failure, with [lastError] set. */
    @Synchronized
    fun exec(context: Context, command: String): Boolean {
        if (!ensureConnected(context)) return false
        return runCatching { openStream("shell:$command") }.getOrElse {
            // A dead socket looks exactly like a failed command, so drop it and let the next press
            // reconnect rather than answering false forever.
            Logger.w("ADB: command failed — ${it.message}")
            disconnect()
            false
        }
    }

    /** Drops the connection. The next call reconnects. */
    @Synchronized
    fun disconnect() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null; pushback = null
        connected = false
    }

    /** Forgets the failure state so Settings can retry after the user changes something. */
    fun retry() {
        disconnect()
        lastError = null
        refused = false
    }

    // ── Connection ───────────────────────────────────────────────────────────

    private fun ensureConnected(context: Context): Boolean {
        if (connected && socket?.isConnected == true && socket?.isClosed == false) return true
        // A refusal is permanent for this process. Retrying opened a fresh socket on EVERY key press
        // -- several a second while someone holds a direction -- to be hung up on identically each
        // time. [retry] clears it if the user changes something.
        if (refused) return false
        disconnect()
        return runCatching { connect(context) }.getOrElse {
            lastError = it.message ?: it.javaClass.simpleName
            if (it is RefusedByDaemon) {
                refused = true
                Logger.i("ADB: unavailable on this device — falling back to the accessibility service")
            } else {
                Logger.w("ADB: connect failed — $lastError")
            }
            disconnect()
            false
        }
    }

    /**
     * Every address worth dialling, best first.
     *
     * Our own LAN address leads because it is the one that stands a chance: see the note in
     * [connect]. Loopback is kept last rather than dropped — it is the correct address on any device
     * whose adbd does not police self-attach, and costs one refused socket where it isn't.
     */
    private fun candidateHosts(): List<String> =
        listOfNotNull(localAddress(), HOST).distinct()

    /**
     * This device's own address on the LAN. Enumerated from the interfaces rather than read from
     * WifiManager so a Fire TV on ethernet is handled the same as one on wifi.
     */
    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()

    /**
     * Decides whether adbd intends to serve this connection, before we commit to it.
     *
     * "Broken pipe" on the first write means the socket was accepted and then reset, which could be
     * either adbd disliking our banner or adbd refusing us outright. Those need completely different
     * responses, and guessing between them has already cost a build. A short read separates them:
     * adbd stays silent and waits for CNXN on a connection it intends to serve, so an immediate
     * end-of-stream means it hung up before we said a word.
     *
     * Leaves [pushback] and [input] wired to the surviving socket, with any greeting byte unread.
     */
    private fun survivesRefusalProbe(sock: Socket, host: String): Boolean {
        val pb = PushbackInputStream(sock.getInputStream(), 1)
        val stream = DataInputStream(pb)
        sock.soTimeout = PROBE_TIMEOUT_MS
        val probe = runCatching { stream.read() }.getOrElse { -2 }
        sock.soTimeout = READ_TIMEOUT_MS
        if (probe == -1) {
            Logger.i("ADB: adbd hung up on $host before the handshake — refusing connections from there")
            return false
        }
        if (probe >= 0) {
            pb.unread(probe)
            Logger.i("ADB: adbd greeted us first (0x${probe.toString(16)}) — pushed back")
        } else {
            Logger.i("ADB: adbd on $host is waiting for CNXN, as expected")
        }
        pushback = pb
        input = stream
        Logger.i("ADB: socket open to $host:$PORT")
        return true
    }

    /** adbd hung up before the handshake — a policy decision, not a protocol error. */
    private class RefusedByDaemon(message: String) : IllegalStateException(message)

    /** Set once adbd refuses us, so we stop opening a socket per key press. */
    @Volatile private var refused = false

    private fun connect(context: Context): Boolean {
        // Generated BEFORE the socket is opened. RSA-2048 keygen took ~700ms in the device log, and
        // doing it after connecting left the socket sitting idle for that whole time, which is one
        // more reason adbd might hang up that has nothing to do with the protocol.
        val keys = loadOrCreateKey(context)

        // Try each candidate address before declaring defeat. adbd's self-attach block is a check on
        // the PEER address of the incoming connection: a connection from 127.0.0.1 is visibly the
        // device talking to itself and gets hung up on before the handshake, which is exactly what
        // the device log showed. Dialling our own LAN address instead sends the packets out through
        // the network stack, so the peer address adbd sees is 192.168.x.x -- indistinguishable from
        // the developer machine it is happy to serve. Whether Fire OS blocks that too is the open
        // question this answers; loopback stays in the list as the fallback for devices that allow it.
        var sock: Socket? = null
        var lastFailure: Throwable? = null
        for (host in candidateHosts()) {
            val attempt = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
            }
            if (attempt.isFailure) {
                lastFailure = attempt.exceptionOrNull()
                Logger.i("ADB: $host:$PORT unreachable — ${lastFailure?.message}")
                continue
            }
            val candidate = attempt.getOrThrow()
            // A socket that opens is not a socket adbd will serve. Probe this one before committing,
            // so a loopback refusal doesn't consume the attempt the LAN address would have won.
            if (survivesRefusalProbe(candidate, host)) { sock = candidate; break }
            runCatching { candidate.close() }
            lastFailure = RefusedByDaemon("adbd refused the connection from $host before the handshake")
        }
        if (sock == null) {
            throw (lastFailure as? RefusedByDaemon)
                ?: RefusedByDaemon("no address reachable on port $PORT — ${lastFailure?.message}")
        }
        socket = sock
        output = sock.getOutputStream()

        // NUL-terminated, like every adb payload -- adbd parses these as C strings.
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray())
        Logger.i("ADB: CNXN sent")

        // adbd may challenge more than once: token first, and if the signature is rejected it sends
        // another token, at which point we offer the public key and the user sees the dialog.
        var signaturesTried = 0
        while (true) {
            val msg = receive()
            when (msg.command) {
                A_CNXN -> {
                    connected = true
                    lastError = null
                    Logger.i("ADB: connected — ${String(msg.payload).trim('\u0000')}")
                    return true
                }

                A_AUTH -> {
                    if (msg.arg0 != AUTH_TOKEN) throw IllegalStateException("Unexpected AUTH ${msg.arg0}")
                    if (signaturesTried == 0) {
                        Logger.i("ADB: AUTH token received — signing")
                        send(A_AUTH, AUTH_SIGNATURE, 0, sign(keys.private as RSAPrivateKey, msg.payload))
                        signaturesTried++
                    } else {
                        // Our key is not in adb_keys yet. Offering the public key is what makes the
                        // "Allow debugging?" dialog appear on the television.
                        Logger.i("ADB: signature rejected — sending public key (expect the on-screen prompt)")
                        lastError = "Waiting for the \"Allow debugging?\" prompt on the TV"
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, encodePublicKey(keys.public as RSAPublicKey))
                    }
                }

                else -> throw IllegalStateException("Unexpected 0x${msg.command.toString(16)} during connect")
            }
        }
    }

    /**
     * Opens a stream, drains its output, and closes it.
     *
     * We do not care what the command prints -- `input keyevent` prints nothing on success -- but
     * the payload has to be read anyway or it backs up in the socket and desynchronises the next
     * command.
     */
    private fun openStream(destination: String): Boolean {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, "$destination\u0000".toByteArray())
        while (true) {
            val msg = receive()
            when (msg.command) {
                A_OKAY -> Unit                     // stream accepted; keep reading until it closes
                A_WRTE -> send(A_OKAY, localId, msg.arg0, ByteArray(0))
                A_CLSE -> {
                    send(A_CLSE, localId, msg.arg0, ByteArray(0))
                    return true
                }
                else -> return false
            }
        }
    }

    // ── Wire format ──────────────────────────────────────────────────────────

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("No adb socket")
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) })
        header.putInt(command.inv())
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun receive(): Message {
        val stream = input ?: throw IllegalStateException("No adb socket")
        val header = ByteArray(HEADER_SIZE)
        stream.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        buf.int                                     // checksum, unverified — adbd does not require it
        val magic = buf.int
        if (magic != command.inv()) throw IllegalStateException("Corrupt adb header")
        val payload = ByteArray(length)
        if (length > 0) stream.readFully(payload)
        return Message(command, arg0, arg1, payload)
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    /**
     * Our RSA identity, generated once and stored in app-private files.
     *
     * It must be stable: the "Always allow" tick stores THIS key's fingerprint on the device, so
     * regenerating it would silently re-prompt the user on every launch.
     */
    private fun loadOrCreateKey(context: Context): KeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (privFile.exists() && pubFile.exists()) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())),
                )
            }.onFailure { Logger.w("ADB: stored key unreadable, regenerating — ${it.message}") }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        privFile.writeBytes(pair.private.encoded)
        pubFile.writeBytes(pair.public.encoded)
        Logger.i("ADB: generated a new key pair")
        return pair
    }

    /**
     * Signs the AUTH token.
     *
     * The token IS already a SHA-1 digest, so this is a raw PKCS#1 v1.5 signature over the ASN.1
     * DigestInfo wrapper plus the token -- "NONEwithRSA" with the prefix prepended by hand. Using
     * SHA1withRSA instead would hash the digest a second time and adbd would reject it.
     */
    private fun sign(key: RSAPrivateKey, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }

    /**
     * Encodes the public key the way adbd expects, which is not any standard format.
     *
     * It is a little-endian C struct: word count, -1/n[0] mod 2^32, the modulus, R^2 mod n, and the
     * exponent -- then base64, then a space and a user@host label. Getting the two derived values
     * (n0inv and rr) wrong is silently fatal, which is why they are computed rather than copied.
     */
    private fun encodePublicKey(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + WORDS * 4 + WORDS * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toLong().toInt())
        putWords(buf, n)
        putWords(buf, rr)
        buf.putInt(key.publicExponent.toInt())

        // android.util.Base64, not java.util.Base64 — the latter is API 26 and the firetv flavour
        // builds against minSdk 25.
        val encoded = android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)
        return "$encoded phairplay@firetv\u0000".toByteArray()
    }

    /** Writes [value] as [WORDS] little-endian 32-bit words. */
    private fun putWords(buf: ByteBuffer, value: BigInteger) {
        var remaining = value
        repeat(WORDS) {
            buf.putInt(remaining.mod(BigInteger.ONE.shiftLeft(32)).toLong().toInt())
            remaining = remaining.shiftRight(32)
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    /** adbd's own port. Loopback only — this never touches the network. */
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555

    private const val CONNECT_TIMEOUT_MS = 2_000
    /** Short: we are only asking whether adbd hangs up immediately. */
    private const val PROBE_TIMEOUT_MS = 500
    /** Generous, because the first connect waits on a human accepting a dialog. */
    private const val READ_TIMEOUT_MS = 30_000

    private const val HEADER_SIZE = 24
    private const val VERSION = 0x01000000
    private const val MAX_PAYLOAD = 256 * 1024

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private const val KEY_BITS = 2048
    private const val WORDS = KEY_BITS / 32

    /** ASN.1 DigestInfo header for SHA-1, per PKCS#1 v1.5. */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )
}
