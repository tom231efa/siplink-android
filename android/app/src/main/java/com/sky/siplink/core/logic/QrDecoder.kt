package com.sky.siplink.core.logic

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Finds a QR code in a camera picture, by the brightness of its pixels (ZXing). */
object QrDecoder {
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")

    /**
     * The text of the QR code in the picture, null where none is found.
     *
     * @param luminance one byte per pixel, row after row, each row [rowStride] bytes long
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            null
        } catch (e: FormatException) {
            null
        }
    }
}
