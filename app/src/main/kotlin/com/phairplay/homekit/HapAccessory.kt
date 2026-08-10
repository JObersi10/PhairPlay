package com.phairplay.homekit

/**
 * HapAccessory — the accessory database a controller reads from `GET /accessories`.
 *
 * WHY hand-rolled JSON: the shape is small, fixed, and has two rules that a generic serializer
 * gets wrong. Numeric characteristic values must NOT be quoted while string ones must be, and the
 * `value` key has to be omitted entirely for write-only characteristics (a null there makes iOS
 * reject the whole database). Emitting it directly keeps both rules visible.
 *
 * Instance IDs (`iid`) must be unique within an accessory and STABLE across restarts: a controller
 * caches them and will address a characteristic that has silently become a different one. [Ids]
 * assigns them from fixed constants rather than a counter for exactly that reason.
 */

/** Permission strings from the HAP spec: paired read, paired write, event notification. */
object HapPerm {
    const val READ = "pr"
    const val WRITE = "pw"
    const val NOTIFY = "ev"
}

/** HAP characteristic formats. */
object HapFormat {
    const val BOOL = "bool"
    const val UINT8 = "uint8"
    const val UINT32 = "uint32"
    const val STRING = "string"
}

/**
 * One characteristic. [value] is mutable because a characteristic IS the state — a controller reads
 * it, writes it, and subscribes to changes on it.
 *
 * @param onWrite invoked when a controller writes; null means the write is accepted and stored
 *   without a side effect. Runs on the HAP connection thread, so it must not block.
 */
class HapCharacteristic(
    val iid: Int,
    val type: String,
    val format: String,
    val perms: List<String>,
    value: Any? = null,
    val maxValue: Int? = null,
    val validValues: List<Int>? = null,
    val onWrite: ((Any) -> Unit)? = null,
) {
    @Volatile var value: Any? = value

    /** Controllers subscribe per-characteristic; only subscribed ones may be pushed as EVENTs. */
    @Volatile var subscribed: Boolean = false

    val readable: Boolean get() = HapPerm.READ in perms
    val writable: Boolean get() = HapPerm.WRITE in perms

    fun toJson(aid: Int, includeMeta: Boolean): String = buildString {
        append("{\"aid\":").append(aid)
        append(",\"iid\":").append(iid)
        append(",\"type\":\"").append(type).append('"')
        if (includeMeta) {
            append(",\"format\":\"").append(format).append('"')
            append(",\"perms\":[").append(perms.joinToString(",") { "\"$it\"" }).append(']')
            maxValue?.let { append(",\"maxValue\":").append(it) }
            validValues?.let { append(",\"valid-values\":[").append(it.joinToString(",")).append(']') }
        }
        // Write-only characteristics must omit `value` entirely rather than send null.
        if (readable) append(",\"value\":").append(jsonValue(value))
        append('}')
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "1" else "0"     // HAP encodes bools as 0/1
        is Number -> v.toString()
        else -> '"' + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"'
    }
}

class HapService(
    val iid: Int,
    val type: String,
    val characteristics: List<HapCharacteristic>,
    val primary: Boolean = false,
    val linked: List<Int> = emptyList(),
) {
    fun toJson(aid: Int): String = buildString {
        append("{\"iid\":").append(iid)
        append(",\"type\":\"").append(type).append('"')
        if (primary) append(",\"primary\":true")
        if (linked.isNotEmpty()) append(",\"linked\":[").append(linked.joinToString(",")).append(']')
        append(",\"characteristics\":[")
        append(characteristics.joinToString(",") { it.toJson(aid, includeMeta = true) })
        append("]}")
    }
}

class HapAccessory(val aid: Int, val services: List<HapService>) {

    private val byIid: Map<Int, HapCharacteristic> =
        services.flatMap { it.characteristics }.associateBy { it.iid }

    fun characteristic(iid: Int): HapCharacteristic? = byIid[iid]

    fun allCharacteristics(): Collection<HapCharacteristic> = byIid.values

    fun toJson(): String =
        "{\"aid\":$aid,\"services\":[" + services.joinToString(",") { it.toJson(aid) } + "]}"

    companion object {
        fun database(accessories: List<HapAccessory>): String =
            "{\"accessories\":[" + accessories.joinToString(",") { it.toJson() } + "]}"
    }
}

/** HAP service UUIDs (short form). */
object HapServiceType {
    const val ACCESSORY_INFORMATION = "3E"
    const val TELEVISION = "D8"
    const val INPUT_SOURCE = "D9"
    const val TELEVISION_SPEAKER = "113"
}

/** HAP characteristic UUIDs (short form). */
object HapCharType {
    const val IDENTIFY = "14"
    const val MANUFACTURER = "20"
    const val MODEL = "21"
    const val NAME = "23"
    const val SERIAL_NUMBER = "30"
    const val FIRMWARE_REVISION = "52"

    const val ACTIVE = "B0"
    const val ACTIVE_IDENTIFIER = "E7"
    const val CONFIGURED_NAME = "E3"
    const val SLEEP_DISCOVERY_MODE = "E8"
    const val REMOTE_KEY = "E1"

    /** InputSource's own Identifier — what ActiveIdentifier on the Television refers to. */
    const val IDENTIFIER_CHAR = "E6"
    const val INPUT_SOURCE_TYPE = "DB"
    const val IS_CONFIGURED = "D6"
    const val CURRENT_VISIBILITY_STATE = "135"

    const val MUTE = "11A"
    const val VOLUME_CONTROL_TYPE = "E9"
    const val VOLUME_SELECTOR = "EA"
}

/**
 * Stable instance IDs. Never renumber these — a controller caches iids and would silently address
 * the wrong characteristic after an update.
 */
object Ids {
    const val INFO_SERVICE = 1
    const val INFO_NAME = 2
    const val INFO_MANUFACTURER = 3
    const val INFO_MODEL = 4
    const val INFO_SERIAL = 5
    const val INFO_FIRMWARE = 6
    const val INFO_IDENTIFY = 7

    const val TV_SERVICE = 10
    const val TV_ACTIVE = 11
    const val TV_ACTIVE_IDENTIFIER = 12
    const val TV_CONFIGURED_NAME = 13
    const val TV_SLEEP_DISCOVERY = 14
    const val TV_REMOTE_KEY = 15

    const val SPEAKER_SERVICE = 20
    const val SPEAKER_ACTIVE = 21
    const val SPEAKER_VOLUME_CONTROL_TYPE = 22
    const val SPEAKER_VOLUME_SELECTOR = 23
    const val SPEAKER_MUTE = 24

    /** Input sources occupy a block from here, 10 iids apart, so sources can be added safely. */
    const val INPUT_BASE = 100
    const val INPUT_STRIDE = 10
}

/** RemoteKey values the Home app / Control Center remote sends (HAP spec table 6-19). */
object RemoteKey {
    const val REWIND = 0
    const val FAST_FORWARD = 1
    const val NEXT_TRACK = 2
    const val PREVIOUS_TRACK = 3
    const val ARROW_UP = 4
    const val ARROW_DOWN = 5
    const val ARROW_LEFT = 6
    const val ARROW_RIGHT = 7
    const val SELECT = 8
    const val BACK = 9
    const val EXIT = 10
    const val PLAY_PAUSE = 11
    const val INFORMATION = 15
}

/** VolumeSelector values: the Home app sends these for volume up/down. */
object VolumeSelector {
    const val INCREMENT = 0
    const val DECREMENT = 1
}
