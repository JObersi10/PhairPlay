package com.phairplay.homekit

import android.content.Context
import com.phairplay.util.Logger
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * HapStore — persistent HomeKit identity and pairings.
 *
 * Three things MUST survive a restart or the controller silently loses the accessory:
 *  - the accessory's Ed25519 long-term key (its identity in pair-verify),
 *  - the accessory pairing ID, a random MAC-shaped string that must never change,
 *  - the paired controllers' public keys.
 *
 * [configNumber] also persists: HomeKit requires it to increment whenever the accessory's
 * service/characteristic layout changes, and a controller that sees the same number assumes its
 * cached database is still valid. Regenerating it from scratch on each boot would be worse than
 * useless — it would make iOS cache a layout it never re-reads.
 *
 * Deliberately SharedPreferences, matching PairingKeys: the values are small, and this has to be
 * readable synchronously from the HAP connection thread during a handshake.
 */
class HapStore(context: Context) : HapPairingStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stable accessory pairing ID, formatted like a MAC address as HomeKit expects. */
    val accessoryId: String = prefs.getString(KEY_ID, null) ?: newAccessoryId().also {
        prefs.edit().putString(KEY_ID, it).apply()
    }

    /**
     * The 8-digit setup code, generated once and shown on the TV.
     *
     * Persisted rather than regenerated so the code on screen matches what a controller half-way
     * through pairing already has, and so the user can re-open the pairing screen and see the same
     * digits they wrote down.
     */
    val setupCode: String = prefs.getString(KEY_SETUP_CODE, null) ?: newSetupCode().also {
        prefs.edit().putString(KEY_SETUP_CODE, it).apply()
    }

    /**
     * Setup ID: 4 uppercase alphanumeric characters, part of the pairing QR payload and the mDNS
     * `sh` hash that lets iOS show "Tap to set up" without scanning anything.
     */
    val setupId: String = prefs.getString(KEY_SETUP_ID, null) ?: newSetupId().also {
        prefs.edit().putString(KEY_SETUP_ID, it).apply()
    }

    private val ltsk: Ed25519PrivateKeyParameters = run {
        val seed = prefs.getString(KEY_LTSK, null)?.let(::hexToBytes)
            ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                prefs.edit().putString(KEY_LTSK, bytesToHex(it)).apply()
            }
        Ed25519PrivateKeyParameters(seed, 0)
    }

    override fun accessoryLtsk(): Ed25519PrivateKeyParameters = ltsk
    override fun accessoryLtpk(): ByteArray = ltsk.generatePublicKey().encoded

    @Synchronized
    override fun addPairing(controllerId: String, ltpk: ByteArray, admin: Boolean) {
        val map = pairedControllers().toMutableMap()
        map[controllerId] = ltpk
        writePairings(map)
    }

    @Synchronized
    override fun removePairing(controllerId: String) {
        val map = pairedControllers().toMutableMap()
        map.remove(controllerId)
        writePairings(map)
    }

    override fun pairedKey(controllerId: String): ByteArray? = pairedControllers()[controllerId]

    override fun pairedControllers(): Map<String, ByteArray> {
        val raw = prefs.getString(KEY_PAIRINGS, "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) null else parts[0] to hexToBytes(parts[1])
        }.toMap()
    }

    override fun isPaired(): Boolean = pairedControllers().isNotEmpty()

    @Synchronized
    override fun recordFailedAttempt(): Int {
        val n = failedAttempts() + 1
        prefs.edit().putInt(KEY_FAILED, n).apply()
        return n
    }

    override fun failedAttempts(): Int = prefs.getInt(KEY_FAILED, 0)

    override fun clearFailedAttempts() {
        prefs.edit().putInt(KEY_FAILED, 0).apply()
    }

    /**
     * Current config number, and [bumpConfigNumber] to advance it.
     *
     * HomeKit caps this at a 32-bit value and expects it to only ever increase; controllers compare
     * it against their cached copy to decide whether to re-read /accessories.
     */
    val configNumber: Int get() = prefs.getInt(KEY_CONFIG, 1)

    fun bumpConfigNumber() {
        prefs.edit().putInt(KEY_CONFIG, configNumber + 1).apply()
        Logger.i("HAP config number → ${configNumber}")
    }

    /** Wipes the HomeKit identity entirely — used when the user resets pairings from Settings. */
    @Synchronized
    fun reset() {
        prefs.edit()
            .remove(KEY_PAIRINGS).remove(KEY_FAILED)
            .apply()
        bumpConfigNumber()
        Logger.i("HAP pairings reset — accessory is discoverable again")
    }

    private fun writePairings(map: Map<String, ByteArray>) {
        val raw = map.entries.joinToString(";") { "${it.key}:${bytesToHex(it.value)}" }
        prefs.edit().putString(KEY_PAIRINGS, raw).apply()
    }

    companion object {
        private const val PREFS = "phairplay_homekit"
        private const val KEY_ID = "accessory_id"
        private const val KEY_LTSK = "accessory_ltsk"
        private const val KEY_PAIRINGS = "pairings"
        private const val KEY_FAILED = "failed_attempts"
        private const val KEY_CONFIG = "config_number"
        private const val KEY_SETUP_CODE = "setup_code"
        private const val KEY_SETUP_ID = "setup_id"

        /**
         * Codes HomeKit rejects outright: trivially guessable sequences and the all-same digits.
         * Generating one of these would produce an accessory iOS refuses to pair with, with no
         * useful error shown to the user.
         */
        private val FORBIDDEN_CODES = setOf(
            "00000000", "11111111", "22222222", "33333333", "44444444",
            "55555555", "66666666", "77777777", "88888888", "99999999",
            "12345678", "87654321",
        )

        private fun newAccessoryId(): String {
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return b.joinToString(":") { "%02X".format(it) }
        }

        private fun newSetupCode(): String {
            val random = SecureRandom()
            var code: String
            do {
                code = (1..8).map { random.nextInt(10) }.joinToString("")
            } while (code in FORBIDDEN_CODES)
            return code
        }

        private fun newSetupId(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val random = SecureRandom()
            return (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** `123-45-678` — the form HomeKit displays and expects the user to type. */
        fun formatSetupCode(code: String): String =
            "${code.substring(0, 3)}-${code.substring(3, 5)}-${code.substring(5, 8)}"

        private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        private fun hexToBytes(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
