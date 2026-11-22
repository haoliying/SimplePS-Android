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

/** 图片显示画布：支持矩形框选、前景/背景笔刷、双指缩放平移、点击放置文字。 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { NONE, RECT, FG, BG, PLACE }

    var source: Bitmap? = null
    var mode = Mode.NONE
    var brushRadius = 24f
    var rect: Rect? = null
    var onRectChanged: ((Rect?) -> Unit)? = null
    var onPlace: ((Int, Int) -> Unit)? = null

    private var fgMask: Bitmap? = null
    private var bgMask: Bitmap? = null
    private var baseScale = 1f
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var ox = 0f
    private var oy = 0f
    private var placePoint: PointF? = null
    private var last: PointF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 手势状态
    private var mode0 = 0
    private var lastMid = PointF()
    private var lastDist = 0f
    private var panning = false
    private var panStart = PointF()

    fun setImage(bmp: Bitmap?) {
        source = bmp
        fgMask = bmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        bgMask = bmp?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }
        rect = null
        placePoint = null
        resetView()
        invalidate()
    }

    fun setResult(bmp: Bitmap) {
        source = bmp
        if (fgMask == null || fgMask!!.width != bmp.width || fgMask!!.height != bmp.height) {
            fgMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            bgMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        }
        rect = null
        placePoint = null
        invalidate()
    }

    fun getFgMask(): Bitmap? = fgMask
    fun getBgMask(): Bitmap? = bgMask

    fun clearMarks() {
        fgMask?.eraseColor(Color.TRANSPARENT)
        bgMask?.eraseColor(Color.TRANSPARENT)
        rect = null
        placePoint = null
        last = null
        invalidate()
    }

    /** 切换到可平移/缩放的浏览模式。 */
    fun enablePanMode() { mode = Mode.NONE; invalidate() }

    private fun resetView() { userScale = 1f; panX = 0f; panY = 0f }

    private fun drawChecker(canvas: Canvas, dst: RectF) {
        val cell = 24f
        var row = 0
        var y = dst.top
        while (y < dst.bottom) {
            var col = 0
            var x = dst.left
            while (x < dst.right) {
                paint.color = if ((row + col) % 2 == 0) Color.rgb(42, 46, 55) else Color.rgb(30, 33, 40)
                paint.style = Paint.Style.FILL
                canvas.drawRect(x, y, min(x + cell, dst.right), min(y + cell, dst.bottom), paint)
                x += cell; col++
            }
            y += cell; row++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 20, 26))
        val src = source
        if (src == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(141, 150, 166)
                textSize = 42f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("点击「选择图片」开始", width / 2f, height / 2f, p)
            return
        }

        baseScale = min(width.toFloat() / src.width, height.toFloat() / src.height)
        val scale = baseScale * userScale
        val dw = src.width * scale
        val dh = src.height * scale
        ox = (width - dw) / 2f + panX
        oy = (height - dh) / 2f + panY
        val dst = RectF(ox, oy, ox + dw, oy + dh)

        drawChecker(canvas, dst)
        paint.alpha = 255
        canvas.drawBitmap(src, null, dst, paint)
        fgMask?.let { canvas.drawBitmap(it, null, dst, paint) }
        bgMask?.let { canvas.drawBitmap(it, null, dst, paint) }

        rect?.let { r ->
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(34, 195, 166)
            paint.strokeWidth = 2.5f
            canvas.drawRect(
                RectF(ox + r.left * scale, oy + r.top * scale, ox + r.right * scale, oy + r.bottom * scale),
                paint,
            )
            paint.style = Paint.Style.FILL
        }
        placePoint?.let { p ->
            val px = ox + p.x * scale
            val py = oy + p.y * scale
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(79, 140, 255)
            paint.strokeWidth = 2.5f
            canvas.drawCircle(px, py, 12f, paint)
            canvas.drawLine(px - 20f, py, px + 20f, py, paint)
            canvas.drawLine(px, py - 20f, px, py + 20f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val src = source ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode0 = event.pointerCount
                if (event.pointerCount >= 2) {
                    lastDist = dist(event)
                    lastMid = mid(event)
                    panning = true
                }
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                mode0 = 1
                if (mode == Mode.NONE) {
                    panning = true
                    panStart.set(event.x - panX, event.y - panY)
                    invalidate()
                    return true
                }
                val ix = toImageX(event.x); val iy = toImageY(event.y)
                if (mode == Mode.PLACE) {
                    placePoint = PointF(ix, iy)
                    onPlace?.invoke(ix.toInt().coerceIn(0, src.width - 1), iy.toInt().coerceIn(0, src.height - 1))
                    invalidate()
                    return true
                }
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
                if (mode0 >= 2 && event.pointerCount >= 2) {
                    val d = dist(event); val m = mid(event)
                    if (lastDist > 0f) userScale = (userScale * d / lastDist).coerceIn(0.3f, 6f)
                    panX += m.x - lastMid.x
                    panY += m.y - lastMid.y
                    lastDist = d
                    lastMid = m
                    invalidate()
                    return true
                }
                if (mode == Mode.NONE && panning) {
                    panX = event.x - panStart.x
                    panY = event.y - panStart.y
                    invalidate()
                    return true
                }
                if (mode == Mode.NONE) return false
                val ix = toImageX(event.x); val iy = toImageY(event.y)
                if (mode == Mode.RECT) {
                    rect?.let { r ->
                        r.set(min(r.left, ix.toInt()), min(r.top, iy.toInt()),
                            max(r.right, ix.toInt()), max(r.bottom, iy.toInt()))
                        onRectChanged?.invoke(r)
                    }
                } else if (mode != Mode.PLACE) {
                    last?.let { paintStroke(it.x, it.y, ix, iy) }
                    last = PointF(ix, iy)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (mode0 >= 2) { mode0 = 0; lastDist = 0f; return true }
                panning = false
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

    private fun toImageX(x: Float): Float = ((x - ox) / (baseScale * userScale)).coerceIn(0f, (source?.width ?: 1) - 1f)
    private fun toImageY(y: Float): Float = ((y - oy) / (baseScale * userScale)).coerceIn(0f, (source?.height ?: 1) - 1f)
    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    private fun mid(e: MotionEvent): PointF = PointF((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)

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
