package com.phairplay.homekit

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.util.Logger
import java.security.MessageDigest

/**
 * What HomeKit is allowed to do to the device. Implemented by the service; kept as an interface so
 * the accessory layer has no idea what a Fire TV is and can be exercised without one.
 */
interface HomeKitActions {
    /** Home app power toggle. "Off" cannot literally cut power — see [HomeKitReceiver] notes. */
    fun setActive(on: Boolean)
    /** A key from the Home app remote / Control Center remote. Values are [RemoteKey] constants. */
    fun remoteKey(key: Int)
    fun volumeStep(up: Boolean)
    fun setMute(muted: Boolean)
    fun selectInput(identifier: Int)
    /** Home app "Identify" — flash something so the user can tell which device this is. */
    fun identify()
}

/**
 * HomeKitReceiver — exposes PhairPlay to HomeKit as a Television accessory.
 *
 * WHAT WORKS, and what cannot:
 *
 *  - **Power "off"** is not real power. A normal Android app cannot power down a Fire TV; there is
 *    no public API and the key injection that would do it needs a system permission. What "off"
 *    does here is end the streaming session and drop out of the foreground, and — if the user has
 *    granted device-admin — call `lockNow()`, which on Fire OS blanks the display and is the
 *    closest thing to standby available. Reported honestly rather than pretending.
 *
 *  - **Remote keys** cannot be injected system-wide either (INJECT_EVENTS is signature-level).
 *    They do two useful things instead: transport keys (play/pause, next, previous, skip) are
 *    forwarded to the *connected sender* over DACP, which is genuinely end-to-end — pressing pause
 *    in the Home app pauses the iPhone that is streaming. Navigation keys drive PhairPlay's own UI.
 *
 *  - **Volume** maps to the device's own audio stream, which is real and unconditional.
 *
 * Because it is a Television accessory (category 31), iOS surfaces it in the Home app AND in the
 * Control Center remote, which is what makes "use the remote app on my iPhone" work.
 */
class HomeKitReceiver(
    private val context: Context,
    private val actions: HomeKitActions,
    private val deviceName: () -> String,
    /**
     * Extra input sources beyond AirPlay and DLNA, as identifier-to-label pairs.
     *
     * These are the user's app shortcuts. Read once when the accessory is built, because the HAP
     * accessory database is static for the life of a connection — changing them restarts the
     * service, and [HapStore.syncConfigNumber] makes controllers re-read the new layout.
     */
    private val extraInputs: List<Pair<Int, String>> = emptyList(),
    /**
     * Whether to expose the remote at all.
     *
     * Gating only the key handler still left RemoteKey in the accessory database, so iOS kept
     * offering a remote in Control Center that silently did nothing — worse than no remote. When
     * this is false the characteristic is omitted entirely and the remote is simply not there.
     *
     * Read once, at build time: the HAP accessory database is fixed for the life of a connection,
     * and changing the setting restarts the receiver, which rebuilds this and bumps the config
     * number so controllers re-read the layout.
     */
    private val remoteEnabled: Boolean = true,
) {

    private val store = HapStore(context)
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var server: HapServer? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var started = false

    /** The pairing code to display, formatted `123-45-678`. */
    val setupCode: String get() = HapStore.formatSetupCode(store.setupCode)

    val isPaired: Boolean get() = store.isPaired()

    /**
     * The name the Home app will list under "Add Accessory".
     *
     * Worth showing on the setup screen verbatim: it is whatever the device advertises, not the
     * display name the user chose for AirPlay, so on a Fire TV it reads as something like `AFTKM`
     * and looks like a stranger's device unless we say so up front.
     */
    val accessoryName: String get() = deviceName()

    /** The `X-HM://` URI for the setup QR code, or null if the stored code is malformed. */
    val pairingUri: String?
        get() = runCatching { HomeKitSetupPayload.uri(store.setupCode, store.setupId) }.getOrNull()

    // ─── Accessory definition ────────────────────────────────────────────────

    private val activeChar = HapCharacteristic(
        iid = Ids.TV_ACTIVE, type = HapCharType.ACTIVE, format = HapFormat.UINT8,
        perms = listOf(HapPerm.READ, HapPerm.WRITE, HapPerm.NOTIFY), value = 0,
        onWrite = { actions.setActive(asInt(it) == 1) },
    )

    private val activeIdentifierChar = HapCharacteristic(
        iid = Ids.TV_ACTIVE_IDENTIFIER, type = HapCharType.ACTIVE_IDENTIFIER, format = HapFormat.UINT32,
        perms = listOf(HapPerm.READ, HapPerm.WRITE, HapPerm.NOTIFY), value = INPUT_AIRPLAY,
        onWrite = { actions.selectInput(asInt(it)) },
    )

    private val configuredNameChar = HapCharacteristic(
        iid = Ids.TV_CONFIGURED_NAME, type = HapCharType.CONFIGURED_NAME, format = HapFormat.STRING,
        perms = listOf(HapPerm.READ, HapPerm.WRITE, HapPerm.NOTIFY), value = deviceName(),
    )

    private val remoteKeyChar = HapCharacteristic(
        iid = Ids.TV_REMOTE_KEY, type = HapCharType.REMOTE_KEY, format = HapFormat.UINT8,
        perms = listOf(HapPerm.WRITE), maxValue = 16,
        onWrite = { actions.remoteKey(asInt(it)) },
    )

    private val muteChar = HapCharacteristic(
        iid = Ids.SPEAKER_MUTE, type = HapCharType.MUTE, format = HapFormat.BOOL,
        perms = listOf(HapPerm.READ, HapPerm.WRITE, HapPerm.NOTIFY), value = false,
        onWrite = { actions.setMute(asInt(it) == 1) },
    )

    private val volumeSelectorChar = HapCharacteristic(
        iid = Ids.SPEAKER_VOLUME_SELECTOR, type = HapCharType.VOLUME_SELECTOR, format = HapFormat.UINT8,
        perms = listOf(HapPerm.WRITE),
        onWrite = { actions.volumeStep(up = asInt(it) == VolumeSelector.INCREMENT) },
    )

    private val accessory: HapAccessory by lazy { buildAccessory() }

    private fun buildAccessory(): HapAccessory {
        val info = HapService(
            iid = Ids.INFO_SERVICE, type = HapServiceType.ACCESSORY_INFORMATION,
            characteristics = listOf(
                HapCharacteristic(Ids.INFO_NAME, HapCharType.NAME, HapFormat.STRING,
                    listOf(HapPerm.READ), deviceName()),
                HapCharacteristic(Ids.INFO_MANUFACTURER, HapCharType.MANUFACTURER, HapFormat.STRING,
                    listOf(HapPerm.READ), "PhairPlay"),
                HapCharacteristic(Ids.INFO_MODEL, HapCharType.MODEL, HapFormat.STRING,
                    listOf(HapPerm.READ), "PhairPlay Receiver"),
                HapCharacteristic(Ids.INFO_SERIAL, HapCharType.SERIAL_NUMBER, HapFormat.STRING,
                    listOf(HapPerm.READ), store.accessoryId),
                HapCharacteristic(Ids.INFO_FIRMWARE, HapCharType.FIRMWARE_REVISION, HapFormat.STRING,
                    listOf(HapPerm.READ), FIRMWARE_REVISION),
                HapCharacteristic(Ids.INFO_IDENTIFY, HapCharType.IDENTIFY, HapFormat.BOOL,
                    listOf(HapPerm.WRITE), onWrite = { actions.identify() }),
            ),
        )

        val inputs = (INPUTS + extraInputs).map { (identifier, label) -> inputSource(identifier, label) }

        val television = HapService(
            iid = Ids.TV_SERVICE, type = HapServiceType.TELEVISION, primary = true,
            // Inputs and the speaker must be LINKED to the television or the Home app shows a bare
            // switch with no remote and no input list.
            linked = inputs.map { it.iid } + Ids.SPEAKER_SERVICE,
            characteristics = listOfNotNull(
                configuredNameChar,
                activeChar,
                activeIdentifierChar,
                remoteKeyChar.takeIf { remoteEnabled },
                HapCharacteristic(Ids.TV_SLEEP_DISCOVERY, HapCharType.SLEEP_DISCOVERY_MODE,
                    HapFormat.UINT8, listOf(HapPerm.READ, HapPerm.NOTIFY), 1),
            ),
        )

        val speaker = HapService(
            iid = Ids.SPEAKER_SERVICE, type = HapServiceType.TELEVISION_SPEAKER,
            characteristics = listOf(
                HapCharacteristic(Ids.SPEAKER_ACTIVE, HapCharType.ACTIVE, HapFormat.UINT8,
                    listOf(HapPerm.READ, HapPerm.NOTIFY), 1),
                // RELATIVE tells iOS to send up/down steps rather than an absolute level, which is
                // all Android's stream volume can honour without fighting the system UI.
                HapCharacteristic(Ids.SPEAKER_VOLUME_CONTROL_TYPE, HapCharType.VOLUME_CONTROL_TYPE,
                    HapFormat.UINT8, listOf(HapPerm.READ, HapPerm.NOTIFY), VOLUME_CONTROL_RELATIVE),
                volumeSelectorChar,
                muteChar,
            ),
        )

        return HapAccessory(aid = 1, services = listOf(info, television) + inputs + speaker)
    }

    private fun inputSource(identifier: Int, label: String): HapService {
        val base = Ids.INPUT_BASE + identifier * Ids.INPUT_STRIDE
        return HapService(
            iid = base, type = HapServiceType.INPUT_SOURCE,
            characteristics = listOf(
                HapCharacteristic(base + 1, HapCharType.IDENTIFIER_CHAR, HapFormat.UINT32,
                    listOf(HapPerm.READ), identifier),
                HapCharacteristic(base + 2, HapCharType.CONFIGURED_NAME, HapFormat.STRING,
                    listOf(HapPerm.READ, HapPerm.WRITE, HapPerm.NOTIFY), label),
                HapCharacteristic(base + 3, HapCharType.INPUT_SOURCE_TYPE, HapFormat.UINT8,
                    listOf(HapPerm.READ), INPUT_SOURCE_TYPE_APPLICATION),
                HapCharacteristic(base + 4, HapCharType.IS_CONFIGURED, HapFormat.UINT8,
                    listOf(HapPerm.READ, HapPerm.NOTIFY), 1),
                HapCharacteristic(base + 5, HapCharType.CURRENT_VISIBILITY_STATE, HapFormat.UINT8,
                    listOf(HapPerm.READ, HapPerm.NOTIFY), 0),
                HapCharacteristic(base + 6, HapCharType.NAME, HapFormat.STRING,
                    listOf(HapPerm.READ), label),
            ),
        )
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true

        val accessories = listOf(accessory)
        // Must happen before the mDNS record goes out: `c#` is read from the store when the TXT
        // record is built, and a controller that sees the old number never re-reads /accessories.
        store.syncConfigNumber(HapAccessory.shapeSignature(accessories))

        val s = HapServer(
            store = store,
            accessories = accessories,
            onIdentify = { actions.identify() },
            // Pairing state changes the `sf` flag, and iOS only re-reads it if the record is
            // re-announced — without this the accessory stays "unpaired" in every controller's
            // browser after pairing, and offers to set it up again.
            onPairedChanged = { reannounce() },
        )
        server = s
        val port = s.start()
        register(port)
        Logger.i("HomeKit started — ${if (isPaired) "paired" else "setup code $setupCode"}")
    }

    fun stop() {
        if (!started) return
        started = false
        unregister()
        server?.stop()
        server = null
        Logger.i("HomeKit stopped")
    }

    /** Clears all pairings so the accessory can be added to a different Home. */
    fun resetPairings() {
        store.reset()
        reannounce()
    }

    // ─── State push ──────────────────────────────────────────────────────────

    /** Reflects a locally-initiated change back to HomeKit so the Home app tile stays truthful. */
    fun reportActive(on: Boolean) = update(activeChar, if (on) 1 else 0)

    fun reportInput(identifier: Int) = update(activeIdentifierChar, identifier)

    fun reportMuted(muted: Boolean) = update(muteChar, muted)

    private fun update(ch: HapCharacteristic, value: Any) {
        if (ch.value == value) return          // no event for a no-op change
        ch.value = value
        server?.notifyChanged(accessory.aid, ch)
    }

    // ─── mDNS ────────────────────────────────────────────────────────────────

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("c#", store.configNumber.toString())   // config number; bump on layout change
            setAttribute("ff", "0")                             // feature flags: no MFi
            setAttribute("id", store.accessoryId)               // stable accessory pairing id
            setAttribute("md", "PhairPlay")                     // model
            setAttribute("pv", "1.1")                           // HAP protocol version
            setAttribute("s#", "1")                             // state number, always 1
            // sf=1 means "discoverable and unpaired". It MUST become 0 once paired, or every
            // controller keeps offering to set up an accessory that is already in a Home.
            setAttribute("sf", if (store.isPaired()) "0" else "1")
            setAttribute("ci", CATEGORY_TELEVISION.toString())
            setAttribute("sh", setupHash())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logger.i("HomeKit mDNS registered: ${info.serviceName} on $port (sf=${if (store.isPaired()) 0 else 1})")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.e("HomeKit mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Logger.e("HomeKit mDNS register threw", it) }
    }

    private fun unregister() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    /** Re-announce so controllers see the new `sf`/`c#`. */
    private fun reannounce() {
        val port = server?.port ?: return
        unregister()
        register(port)
    }

    /**
     * `sh` — base64 of the first 4 bytes of SHA-512(setupId + accessoryId).
     *
     * This is what lets iOS recognise an un-set-up accessory and offer "Tap to set up" without the
     * user scanning a QR code.
     */
    private fun setupHash(): String {
        val material = (store.setupId + store.accessoryId).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-512").digest(material)
        return android.util.Base64.encodeToString(digest.copyOf(4), android.util.Base64.NO_WRAP)
    }

    private fun asInt(v: Any): Int = when (v) {
        is Boolean -> if (v) 1 else 0
        is Number -> v.toInt()
        else -> v.toString().toIntOrNull() ?: 0
    }

    companion object {
        private const val SERVICE_TYPE = "_hap._tcp"
        private const val CATEGORY_TELEVISION = 31
        private const val VOLUME_CONTROL_RELATIVE = 1
        private const val INPUT_SOURCE_TYPE_APPLICATION = 10
        private const val FIRMWARE_REVISION = "1.0"

        const val INPUT_AIRPLAY = 1
        const val INPUT_DLNA = 2

        /**
         * The one built-in input: PhairPlay itself.
         *
         * "AirPlay" and "DLNA" used to be listed separately, but selecting either did the same
         * thing — show PhairPlay — so the Home app offered a choice that wasn't one. The protocol
         * is decided by whatever connects, not by the input the user picks. Everything else in the
         * list is now a user-assigned app shortcut, which is a real choice.
         */
        private val INPUTS = listOf(
            INPUT_AIRPLAY to "PhairPlay",
        )
    }
}
