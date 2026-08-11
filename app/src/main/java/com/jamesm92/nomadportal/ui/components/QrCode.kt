package com.jamesm92.nomadportal.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a black-on-white QR bitmap, [sizePx] square.
 * Pure ZXing-core (`com.google.zxing:core`), no Android-specific wrapper
 * needed for encoding — just a `BitMatrix` painted pixel-by-pixel into a
 * plain [Bitmap]. See `libs.versions.toml`'s own comment for why ZXing
 * over ML Kit (no Google-Play-Services dependency anywhere else in this
 * app, and this keeps it that way).
 *
 * [content] is expected to be this device's own raw hex LXMF address
 * (see [com.jamesm92.nomadportal.ui.settings.SettingsScreen]'s "Show QR
 * code" affordance) — the exact same shape
 * [AddByAddressDialog]/[QrScannerOverlay] already accept from manual
 * entry, so scanning this code round-trips through the identical code
 * path as typing the address by hand.
 */
fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bitmap
}
