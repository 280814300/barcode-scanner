package com.maoge.barcodescanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 在预览之上绘制检测框，并支持"点击扫描区域中指定的某个条码"。
 * 映射采用 FIT_CENTER（与 PreviewView 默认缩放一致），保证框与画面条码对齐。
 */
class BarcodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var detections: List<DetectedBarcode> = emptyList()
    var imageW = 1
    var imageH = 1
    var onSelect: ((value: String, format: String) -> Unit)? = null

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#2000E5FF")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#AA000000")
    }

    /** 返回 [scale, offX, offY]，将解码图像坐标映射到本 View 坐标（FIT_CENTER）。 */
    private fun mapParams(): FloatArray {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = min(viewW / imageW, viewH / imageH)
        val offX = (viewW - imageW * scale) / 2f
        val offY = (viewH - imageH * scale) / 2f
        return floatArrayOf(scale, offX, offY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return
        val (scale, offX, offY) = mapParams()
        for (d in detections) {
            val b = d.boundingBox()
            val left = offX + b.left * scale
            val top = offY + b.top * scale
            val right = offX + b.right * scale
            val bottom = offY + b.bottom * scale
            // 一维码（2 个点）的边框很薄，加厚便于辨认与点击
            val isLine = d.points.size == 4
            val pad = if (isLine) 20f else 6f
            val r = RectF(left - pad, top - pad, right + pad, bottom + pad)
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, boxPaint)

            val label = d.value
            val tw = textPaint.measureText(label)
            val ly = if (top - 38f > 0) top - 38f else bottom + 40f
            canvas.drawRect(left - 4f, ly - 30f, left + tw + 8f, ly + 6f, bgPaint)
            canvas.drawText(label, left, ly, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && detections.isNotEmpty()) {
            val (scale, offX, offY) = mapParams()
            val ix = (event.x - offX) / scale
            val iy = (event.y - offY) / scale
            var best: DetectedBarcode? = null
            var bestD = Float.MAX_VALUE
            for (d in detections) {
                val dd = distToDetection(d, ix, iy)
                if (dd < bestD) {
                    bestD = dd
                    best = d
                }
            }
            if (best != null && bestD < 60f) {
                onSelect?.invoke(best.value, best.format)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distToDetection(d: DetectedBarcode, x: Float, y: Float): Float {
        val pts = d.points
        return if (pts.size == 4) {
            // 一维码：点到线段距离
            pointToSegment(x, y, pts[0], pts[1], pts[2], pts[3])
        } else {
            // 二维码等：点到矩形距离
            val b = d.boundingBox()
            val dx = maxOf(b.left - x, 0f, x - b.right)
            val dy = maxOf(b.top - y, 0f, y - b.bottom)
            sqrt(dx * dx + dy * dy)
        }
    }

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        var t = ((px - ax) * dx + (py - ay) * dy) / len2
        t = maxOf(0f, minOf(1f, t))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }
}
