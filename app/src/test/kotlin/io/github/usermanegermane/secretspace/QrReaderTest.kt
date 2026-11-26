package io.github.usermanegermane.secretspace

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import secretspace.core.AppVersion
import secretspace.core.Totp

/** What the camera hands over, made from a real QR code, read back as the scanner does. */
class QrReaderTest {
    /** A grey camera frame with the code somewhere in it, rows padded as CameraX pads them. */
    private fun frame(text: String, width: Int, height: Int, rowStride: Int, size: Int, left: Int, top: Int): ByteArray {
        val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val out = ByteArray(rowStride * height) { 0x7F }
        for (y in 0 until height) for (x in 0 until width) {
            val cx = x - left
            val cy = y - top
            if (cx in 0 until size && cy in 0 until size) {
                out[y * rowStride + x] = if (code[cx, cy]) 0x10 else 0xF0.toByte()
            }
        }
        return out
    }

    private val link = "otpauth://totp/Bank:me?secret=JBSWY3DPEHPK3PXP&issuer=Bank"

    @Test fun aCodeInTheFrameIsRead() {
        val reader = QrReader()
        assertEquals(link, reader.read(frame(link, 640, 480, 640, 300, 170, 90), 640, 640, 480))
        // a row stride wider than the picture, and the code off centre
        assertEquals(link, reader.read(frame(link, 640, 480, 704, 260, 20, 200), 704, 640, 480))
        // what it gives the 2FA field works there
        assertEquals("", Totp.check(reader.read(frame(link, 640, 480, 640, 300, 170, 90), 640, 640, 480)))
    }

    @Test fun aFrameWithoutACodeIsNothing() {
        assertNull(QrReader().read(ByteArray(640 * 480) { 0x7F }, 640, 640, 480))
    }

    @Test fun theAppsVersionIsTheCoresVersion() {
        assertEquals(AppVersion.VERSION, BuildConfig.VERSION_NAME)
    }
}
