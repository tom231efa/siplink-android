package com.sky.siplink.core.logic

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val address = "https://platform.example.com:8443/p/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE?pin=q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c"

    /** A picture of [text] as a QR code: dark modules 0, light ones 255, each row [stride] bytes long. */
    private fun picture(text: String, size: Int, stride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bytes = ByteArray(stride * size) { 0x55 }
        for (y in 0 until size) for (x in 0 until size) bytes[y * stride + x] = if (matrix[x, y]) 0 else 255.toByte()
        return bytes
    }

    @Test
    fun theAddressIsReadFromThePicture() {
        assertEquals(address, QrDecoder.decode(picture(address, 400, 400), 400, 400, 400))
    }

    @Test
    fun rowsLongerThanThePictureAreRead() {
        // Camera pictures often have rows padded beyond their width.
        assertEquals(address, QrDecoder.decode(picture(address, 400, 448), 400, 400, 448))
    }

    @Test
    fun aPictureWithoutCodeGivesNothing() {
        assertNull(QrDecoder.decode(ByteArray(200 * 200) { 128.toByte() }, 200, 200, 200))
    }
}
