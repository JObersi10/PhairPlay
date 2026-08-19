package com.phairplay.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.phairplay.util.Logger

/**
 * QrCode — renders a string as a QR bitmap for on-screen scanning.
 *
 * Tuned for the one job it has: being photographed off a television across a room. That drives two
 * choices that would be wrong elsewhere — a white quiet zone drawn into the bitmap itself rather
 * than left to the layout (a QR code bleeding into a dark TV background does not scan), and error
 * correction at Q rather than the usual M, because glare and off-axis viewing damage a screen-shown
 * code far more than a printed one.
 */
object QrCode {

    /**
     * Encodes [text] as a square QR bitmap [sizePx] on a side, or null if encoding fails.
     *
     * Returns null rather than throwing: a missing QR code should cost the user the convenience of
     * scanning, not the whole setup screen — the numeric code is always shown alongside it.
     */
    fun render(text: String, sizePx: Int): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            // One int per pixel, set in a single call: setPixel() per module is slow enough to be
            // visible as a stutter when the setup page appears.
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
        }.onFailure { Logger.w("QR encode failed: ${it.message}") }.getOrNull()
    }

    /** Four modules is the spec minimum; anything less and scanners lose the finder patterns. */
    private const val QUIET_ZONE_MODULES = 4
}
