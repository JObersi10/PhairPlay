package com.phairplay.homekit

import com.phairplay.util.Logger
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.SecureRandom

/** Long-term accessory keys plus the paired-controller list. */
interface HapPairingStore {
    fun accessoryLtsk(): Ed25519PrivateKeyParameters
    fun accessoryLtpk(): ByteArray
    fun addPairing(controllerId: String, ltpk: ByteArray, admin: Boolean)
    fun removePairing(controllerId: String)
    fun pairedKey(controllerId: String): ByteArray?
    fun pairedControllers(): Map<String, ByteArray>
    fun isPaired(): Boolean
    fun recordFailedAttempt(): Int
    fun failedAttempts(): Int
    fun clearFailedAttempts()
}

/**
 * HapPairing — `/pair-setup` and `/pair-verify`, the two handshakes gating every HomeKit session.
 *
 * WHY both exist: pair-setup runs ONCE per controller, trading the setup code for a long-term
 * Ed25519 key exchange. pair-verify runs on EVERY subsequent connection, proving possession of
 * those long-term keys through an ephemeral X25519 exchange and deriving the session keys. A
 * paired controller never sends the setup code again.
 *
 * Both are strict state machines. A controller that sends M3 without M1 is broken or probing, and
 * either way gets rejected rather than accommodated. State is per-connection, so an instance
 * belongs to a connection rather than to the server.
 */
class HapPairing(
    private val store: HapPairingStore,
    private val accessoryId: String,
    private val setupCode: () -> String,
    private val onPairedChanged: () -> Unit = {},
) {

    /**
     * Session keys, non-null only after a successful pair-verify.
     *
     * Direction matters and is the classic HAP bug: the controller's write key is our read key.
     * Swapping them pairs cleanly and then fails to decrypt every request that follows.
     */
    class Session(val readKey: ByteArray, val writeKey: ByteArray, val controllerId: String)

    private var srp: HapSrp? = null
    private var srpState = 0

    private var verifyPrivate: X25519PrivateKeyParameters? = null
    private var verifyControllerPk: ByteArray? = null
    private var verifyShared: ByteArray? = null
    private var verifySessionKey: ByteArray? = null
    private var verifyState = 0

    var session: Session? = null
        private set

    // ─── pair-setup ──────────────────────────────────────────────────────────

    fun pairSetup(body: ByteArray): ByteArray {
        val tlv = HapTlv.decode(body)
        val state = tlv[HapTlv.STATE]?.firstOrNull()?.toInt()
        return when (state) {
            1 -> setupM1()
            3 -> setupM3(tlv)
            5 -> setupM5(tlv)
            else -> {
                Logger.w("HAP pair-setup: unexpected state $state")
                HapTlv.error((state ?: 0) + 1, HapTlv.ERROR_UNKNOWN)
            }
        }
    }

    private fun setupM1(): ByteArray {
        // An already-paired accessory that still answered M1 would let anyone holding the code
        // pair again at will. HomeKit's model is: pair once, then manage pairings over a verified
        // session. Refuse here instead.
        if (store.isPaired()) {
            Logger.w("HAP pair-setup refused — already paired")
            return HapTlv.error(2, HapTlv.ERROR_UNAVAILABLE)
        }
        // The setup code is only 8 digits. Without a lockout it is trivially enumerable, so a run
        // of wrong codes has to stop being answerable at all.
        if (store.failedAttempts() >= MAX_SETUP_ATTEMPTS) {
            Logger.w("HAP pair-setup locked out after ${store.failedAttempts()} failed attempts")
            return HapTlv.error(2, HapTlv.ERROR_MAX_TRIES)
        }
        val s = HapSrp(setupCode())
        srp = s
        srpState = 2
        Logger.i("HAP pair-setup M1 → M2 (SRP challenge issued)")
        return HapTlv.encode(
            HapTlv.STATE to HapTlv.byte(2),
            HapTlv.PUBLIC_KEY to unsigned(s.serverPublic),
            HapTlv.SALT to s.salt,
        )
    }

    private fun setupM3(tlv: Map<Int, ByteArray>): ByteArray {
        val s = srp
        if (s == null || srpState != 2) return HapTlv.error(4, HapTlv.ERROR_UNKNOWN)
        val a = tlv[HapTlv.PUBLIC_KEY] ?: return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)
        val proof = tlv[HapTlv.PROOF] ?: return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)

        val m2 = s.verify(a, proof)
        if (m2 == null) {
            val n = store.recordFailedAttempt()
            Logger.w("HAP pair-setup proof failed ($n/$MAX_SETUP_ATTEMPTS)")
            srp = null; srpState = 0
            return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)
        }
        srpState = 4
        Logger.i("HAP pair-setup M3 → M4 (setup code verified)")
        return HapTlv.encode(HapTlv.STATE to HapTlv.byte(4), HapTlv.PROOF to m2)
    }

    private fun setupM5(tlv: Map<Int, ByteArray>): ByteArray {
        val k = srp?.sessionKey
        if (k == null || srpState != 4) return HapTlv.error(6, HapTlv.ERROR_UNKNOWN)
        val encrypted = tlv[HapTlv.ENCRYPTED_DATA] ?: return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)

        val encKey = HapCrypto.hkdf(k, SETUP_ENCRYPT_SALT, SETUP_ENCRYPT_INFO)
        val plain = runCatching {
            HapCrypto.open(encKey, HapCrypto.pairingNonce("PS-Msg05"), encrypted)
        }.getOrElse {
            Logger.w("HAP pair-setup M5 decrypt failed: ${it.message}")
            return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)
        }

        val sub = HapTlv.decode(plain)
        val controllerId = sub[HapTlv.IDENTIFIER] ?: return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)
        val controllerLtpk = sub[HapTlv.PUBLIC_KEY] ?: return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)
        val signature = sub[HapTlv.PROOF] ?: return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)

        // The controller signs (deviceX | pairingId | ltpk) with the very key it is registering.
        // Verifying that is what stops someone registering a public key they do not hold.
        val deviceX = HapCrypto.hkdf(k, CONTROLLER_SIGN_SALT, CONTROLLER_SIGN_INFO)
        val deviceInfo = deviceX + controllerId + controllerLtpk
        val ok = runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(controllerLtpk, 0))
                update(deviceInfo, 0, deviceInfo.size)
            }.verifySignature(signature)
        }.getOrDefault(false)
        if (!ok) {
            Logger.w("HAP pair-setup M5 controller signature invalid")
            return HapTlv.error(6, HapTlv.ERROR_AUTHENTICATION)
        }

        val id = String(controllerId, Charsets.UTF_8)
        store.addPairing(id, controllerLtpk, admin = true)
        store.clearFailedAttempts()
        onPairedChanged()

        // Mirror image, so the controller can trust us on later connections.
        val accessoryX = HapCrypto.hkdf(k, ACCESSORY_SIGN_SALT, ACCESSORY_SIGN_INFO)
        val idBytes = accessoryId.toByteArray(Charsets.UTF_8)
        val ltpk = store.accessoryLtpk()
        val accessoryInfo = accessoryX + idBytes + ltpk
        val accessorySig = Ed25519Signer().apply {
            init(true, store.accessoryLtsk())
            update(accessoryInfo, 0, accessoryInfo.size)
        }.generateSignature()

        val subOut = HapTlv.encode(
            HapTlv.IDENTIFIER to idBytes,
            HapTlv.PUBLIC_KEY to ltpk,
            HapTlv.PROOF to accessorySig,
        )
        val sealed = HapCrypto.seal(encKey, HapCrypto.pairingNonce("PS-Msg06"), subOut)

        srp = null; srpState = 0
        Logger.i("HAP pair-setup complete — controller $id paired")
        return HapTlv.encode(HapTlv.STATE to HapTlv.byte(6), HapTlv.ENCRYPTED_DATA to sealed)
    }

    // ─── pair-verify ─────────────────────────────────────────────────────────

    fun pairVerify(body: ByteArray): ByteArray {
        val tlv = HapTlv.decode(body)
        val state = tlv[HapTlv.STATE]?.firstOrNull()?.toInt()
        return when (state) {
            1 -> verifyM1(tlv)
            3 -> verifyM3(tlv)
            else -> HapTlv.error((state ?: 0) + 1, HapTlv.ERROR_UNKNOWN)
        }
    }

    private fun verifyM1(tlv: Map<Int, ByteArray>): ByteArray {
        val controllerPk = tlv[HapTlv.PUBLIC_KEY] ?: return HapTlv.error(2, HapTlv.ERROR_AUTHENTICATION)

        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey().encoded
        val shared = ByteArray(32)
        X25519Agreement().apply { init(priv) }
            .calculateAgreement(X25519PublicKeyParameters(controllerPk, 0), shared, 0)

        verifyPrivate = priv
        // Held rather than recomputed: M3's signature covers exactly these bytes, and deriving
        // them from anything else would verify the wrong message and still "succeed" structurally.
        verifyControllerPk = controllerPk
        verifyShared = shared

        val idBytes = accessoryId.toByteArray(Charsets.UTF_8)
        val info = pub + idBytes + controllerPk
        val sig = Ed25519Signer().apply {
            init(true, store.accessoryLtsk())
            update(info, 0, info.size)
        }.generateSignature()

        val sessionKey = HapCrypto.hkdf(shared, VERIFY_ENCRYPT_SALT, VERIFY_ENCRYPT_INFO)
        verifySessionKey = sessionKey

        val sub = HapTlv.encode(HapTlv.IDENTIFIER to idBytes, HapTlv.PROOF to sig)
        val sealed = HapCrypto.seal(sessionKey, HapCrypto.pairingNonce("PV-Msg02"), sub)

        verifyState = 2
        return HapTlv.encode(
            HapTlv.STATE to HapTlv.byte(2),
            HapTlv.PUBLIC_KEY to pub,
            HapTlv.ENCRYPTED_DATA to sealed,
        )
    }

    private fun verifyM3(tlv: Map<Int, ByteArray>): ByteArray {
        val shared = verifyShared
        val sessionKey = verifySessionKey
        val controllerPk = verifyControllerPk
        val priv = verifyPrivate
        if (shared == null || sessionKey == null || controllerPk == null || priv == null || verifyState != 2) {
            return HapTlv.error(4, HapTlv.ERROR_UNKNOWN)
        }
        val encrypted = tlv[HapTlv.ENCRYPTED_DATA] ?: return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)

        val plain = runCatching {
            HapCrypto.open(sessionKey, HapCrypto.pairingNonce("PV-Msg03"), encrypted)
        }.getOrElse { return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION) }

        val sub = HapTlv.decode(plain)
        val controllerId = sub[HapTlv.IDENTIFIER]?.toString(Charsets.UTF_8)
            ?: return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)
        val signature = sub[HapTlv.PROOF] ?: return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)

        // The actual access check: only a controller we already paired with may proceed.
        val ltpk = store.pairedKey(controllerId) ?: run {
            Logger.w("HAP pair-verify from unknown controller $controllerId")
            return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)
        }

        val accessoryPk = priv.generatePublicKey().encoded
        val info = controllerPk + controllerId.toByteArray(Charsets.UTF_8) + accessoryPk
        val ok = runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(ltpk, 0))
                update(info, 0, info.size)
            }.verifySignature(signature)
        }.getOrDefault(false)
        if (!ok) {
            Logger.w("HAP pair-verify signature invalid for $controllerId")
            return HapTlv.error(4, HapTlv.ERROR_AUTHENTICATION)
        }

        session = Session(
            readKey = HapCrypto.hkdf(shared, CONTROL_SALT, CONTROL_WRITE_INFO),
            writeKey = HapCrypto.hkdf(shared, CONTROL_SALT, CONTROL_READ_INFO),
            controllerId = controllerId,
        )
        verifyState = 4
        Logger.i("HAP pair-verify complete — session established with $controllerId")
        return HapTlv.encode(HapTlv.STATE to HapTlv.byte(4))
    }

    // ─── /pairings (add, remove, list) over a verified session ───────────────

    /**
     * Handles `POST /pairings`. Only reachable on a verified session, which is the whole access
     * control story: removing the last pairing puts the accessory back into "discoverable and
     * unpaired", so it must not be callable by anyone who has not already paired.
     */
    fun pairings(body: ByteArray): ByteArray {
        val tlv = HapTlv.decode(body)
        if (tlv[HapTlv.STATE]?.firstOrNull()?.toInt() != 1) {
            return HapTlv.error(2, HapTlv.ERROR_UNKNOWN)
        }
        return when (tlv[HapTlv.METHOD]?.firstOrNull()?.toInt()) {
            METHOD_ADD_PAIRING -> {
                val id = tlv[HapTlv.IDENTIFIER]?.toString(Charsets.UTF_8)
                val ltpk = tlv[HapTlv.PUBLIC_KEY]
                if (id == null || ltpk == null) return HapTlv.error(2, HapTlv.ERROR_UNKNOWN)
                store.addPairing(id, ltpk, admin = tlv[HapTlv.PERMISSIONS]?.firstOrNull()?.toInt() == 1)
                onPairedChanged()
                Logger.i("HAP pairing added: $id")
                HapTlv.encode(HapTlv.STATE to HapTlv.byte(2))
            }
            METHOD_REMOVE_PAIRING -> {
                val id = tlv[HapTlv.IDENTIFIER]?.toString(Charsets.UTF_8)
                    ?: return HapTlv.error(2, HapTlv.ERROR_UNKNOWN)
                store.removePairing(id)
                onPairedChanged()
                Logger.i("HAP pairing removed: $id (paired=${store.isPaired()})")
                HapTlv.encode(HapTlv.STATE to HapTlv.byte(2))
            }
            METHOD_LIST_PAIRINGS -> {
                val entries = mutableListOf<Pair<Int, ByteArray>>()
                entries += HapTlv.STATE to HapTlv.byte(2)
                store.pairedControllers().entries.forEachIndexed { i, (id, key) ->
                    // Records repeat per controller, separated by a zero-length separator; without
                    // it a reader cannot tell where one controller's fields end.
                    if (i > 0) entries += HapTlv.SEPARATOR to ByteArray(0)
                    entries += HapTlv.IDENTIFIER to id.toByteArray(Charsets.UTF_8)
                    entries += HapTlv.PUBLIC_KEY to key
                    entries += HapTlv.PERMISSIONS to HapTlv.byte(1)
                }
                HapTlv.encode(*entries.toTypedArray())
            }
            else -> HapTlv.error(2, HapTlv.ERROR_UNKNOWN)
        }
    }

    private fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    companion object {
        /** HAP requires refusing further attempts after repeated wrong setup codes. */
        const val MAX_SETUP_ATTEMPTS = 10

        private const val METHOD_ADD_PAIRING = 3
        private const val METHOD_REMOVE_PAIRING = 4
        private const val METHOD_LIST_PAIRINGS = 5

        private const val SETUP_ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        private const val SETUP_ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        private const val CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        private const val CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        private const val ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
        private const val ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
        private const val VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        private const val VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"
        private const val CONTROL_SALT = "Control-Salt"
        private const val CONTROL_READ_INFO = "Control-Read-Encryption-Key"
        private const val CONTROL_WRITE_INFO = "Control-Write-Encryption-Key"
    }
}
