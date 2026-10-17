package com.maoge.barcodescanner

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * 一次解码返回画面中"所有"条码（多码同框场景）。
 * 完全离线，无需联网下载模型。
 */
data class DetectedBarcode(
    val value: String,
    val format: String,
    val points: FloatArray   // 扁平化的 [x0,y0,x1,y1,...]，坐标位于解码图像空间
) {
    fun boundingBox(): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var i = 0
        while (i < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            i += 2
        }
        return RectF(minX, minY, maxX, maxY)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DetectedBarcode
        return value == other.value && format == other.format && points.contentEquals(other.points)
    }

    override fun hashCode(): Int {
        var result = value.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + points.contentHashCode()
        return result
    }
}

object ZxingMultiDecoder {
    private val reader = GenericMultipleBarcodeReader(
        MultiFormatReader().apply {
            val formats = listOf(
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93, BarcodeFormat.ITF,
                BarcodeFormat.CODABAR, BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417,
                BarcodeFormat.AZTEC
            )
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to formats,
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    )

    /** 在 Bitmap 上解码全部条码。返回空列表表示未识别到。 */
    fun decode(bitmap: Bitmap): List<DetectedBarcode> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            val results = reader.decodeMultiple(binary)
            results.mapNotNull { r ->
                val pts = r.resultPoints ?: return@mapNotNull null
                if (pts.isEmpty()) return@mapNotNull null
                val flat = FloatArray(pts.size * 2)
                for (i in pts.indices) {
                    flat[i * 2] = pts[i].x
                    flat[i * 2 + 1] = pts[i].y
                }
                DetectedBarcode(r.text ?: "", r.barcodeFormat?.name ?: "UNKNOWN", flat)
            }
        } catch (e: NotFoundException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
