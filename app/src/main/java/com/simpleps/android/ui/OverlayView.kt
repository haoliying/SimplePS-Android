package com.simpleps.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** 图片显示 + 矩形框选 + 前景/背景笔刷标记的自定义视图。 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { NONE, RECT, FG, BG }

    var source: Bitmap? = null
    var mode = Mode.NONE
    var brushRadius = 24f
    var rect: Rect? = null
    var onRectChanged: ((Rect?) -> Unit)? = null

    private var fgMask: Bitmap? = null
    private var bgMask: Bitmap? = null
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var last: PointF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setImage(bmp: Bitmap?) {
        source = bmp
        fgMask = bmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        bgMask = bmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        rect = null
        last = null
        invalidate()
    }

    fun getFgMask(): Bitmap? = fgMask
    fun getBgMask(): Bitmap? = bgMask

    fun clearMarks() {
        fgMask?.eraseColor(Color.TRANSPARENT)
        bgMask?.eraseColor(Color.TRANSPARENT)
        rect = null
        last = null
        invalidate()
    }

    fun setResult(bmp: Bitmap) {
        source = bmp
        if (fgMask == null || fgMask!!.width != bmp.width || fgMask!!.height != bmp.height) {
            fgMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            bgMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        }
        clearMarks()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(32, 36, 43))
        val src = source
        if (src == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(141, 150, 166)
                textSize = 42f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("请先选择图片", width / 2f, height / 2f, p)
            return
        }

        scale = min(width.toFloat() / src.width, height.toFloat() / src.height)
        ox = (width - src.width * scale) / 2f
        oy = (height - src.height * scale) / 2f
        val dst = RectF(ox, oy, ox + src.width * scale, oy + src.height * scale)

        canvas.drawBitmap(src, null, dst, paint)
        fgMask?.let { canvas.drawBitmap(it, null, dst, paint) }
        bgMask?.let { canvas.drawBitmap(it, null, dst, paint) }

        rect?.let { r ->
            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = 2.5f
            canvas.drawRect(
                RectF(ox + r.left * scale, oy + r.top * scale, ox + r.right * scale, oy + r.bottom * scale),
                paint,
            )
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val src = source ?: return false
        if (mode == Mode.NONE) return false

        val ix = ((event.x - ox) / scale).coerceIn(0f, src.width - 1f)
        val iy = ((event.y - oy) / scale).coerceIn(0f, src.height - 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.RECT) {
                    rect = Rect(ix.toInt(), iy.toInt(), ix.toInt(), iy.toInt())
                    onRectChanged?.invoke(rect)
                } else {
                    last = PointF(ix, iy)
                    paintStroke(ix, iy, ix, iy)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.RECT) {
                    rect?.let { r ->
                        r.set(min(r.left, ix.toInt()), min(r.top, iy.toInt()),
                            max(r.right, ix.toInt()), max(r.bottom, iy.toInt()))
                        onRectChanged?.invoke(r)
                    }
                } else {
                    last?.let { paintStroke(it.x, it.y, ix, iy) }
                    last = PointF(ix, iy)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.RECT && rect != null) {
                    val r = rect!!
                    if (r.width() < 6 || r.height() < 6) rect = null
                }
                last = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun paintStroke(x0: Float, y0: Float, x1: Float, y1: Float) {
        val mask = if (mode == Mode.FG) fgMask else bgMask
        if (mask == null) return
        val c = Canvas(mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (mode == Mode.FG) Color.argb(255, 0, 220, 140) else Color.argb(255, 255, 70, 70)
            strokeWidth = brushRadius * 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(x0, y0, x1, y1, p)
    }
}
