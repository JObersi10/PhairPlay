package com.phairplay.homekit

/**
 * HomeKitSetupPayload — the `X-HM://` URI encoded in the setup QR code.
 *
 * WHY A QR CODE AT ALL: typing an eight-digit code into a TV remote's on-screen keyboard is the
 * worst part of pairing. Pointing a camera at the screen skips the whole thing, and the Home app
 * treats a scanned payload as a first-class path — it even picks the accessory for you, so the
 * "Add Accessory → More options → pick the device" dance disappears.
 *
 * The payload is a 9-character base-36 encoding of a 64-bit integer, followed by the 4-character
 * setup ID. The integer packs, from the top:
 *
 *     bits 43-45   version   (3 bits, always 0)
 *     bits 40-42   reserved  (4 bits, always 0)
 *     bits 31-38   category  (8 bits — 31 for Television)
 *     bits 27-30   flags     (4 bits — which transports the accessory supports)
 *     bits 0-26    setup code as a plain integer, dashes stripped
 *
 * The setup code occupies 27 bits because the largest valid code, 99999999, needs 27 — which is
 * also why codes are exactly eight digits and not nine.
 */
object HomeKitSetupPayload {

    /** HAP accessory category 31. The Home app uses it to pick the icon and the control layout. */
    const val CATEGORY_TELEVISION = 31

    /** Paired over IP. Set for a Wi-Fi/Ethernet accessory; BLE and NFC accessories set others. */
    const val FLAG_IP = 2

    /**
     * Builds the `X-HM://…` URI for a QR code.
     *
     * @param setupCode the eight-digit code, dashed or not — both forms are accepted.
     * @param setupId the four-character ID advertised as `sh` in the mDNS TXT record. It ties this
     *   QR code to this accessory; a mismatch makes the Home app scan the code and then fail to
     *   find anything to pair with.
     */
    fun uri(
        setupCode: String,
        setupId: String,
        category: Int = CATEGORY_TELEVISION,
        flags: Int = FLAG_IP,
    ): String {
        val digits = setupCode.filter { it.isDigit() }
        require(digits.length == 8) { "HomeKit setup code must be 8 digits, got '${setupCode}'" }
        require(setupId.length == 4) { "HomeKit setup ID must be 4 characters, got '$setupId'" }

        var value = 0L
        value = value or ((category.toLong() and 0xFF) shl 31)
        value = value or ((flags.toLong() and 0x0F) shl 27)
        value = value or (digits.toLong() and 0x7FF_FFFF)

        // Uppercase base 36, left-padded to exactly 9 — the Home app reads a fixed-width field and
        // a short string silently shifts every value it decodes.
        val encoded = value.toString(36).uppercase().padStart(9, '0')
        return "X-HM://$encoded$setupId"
    }
}
