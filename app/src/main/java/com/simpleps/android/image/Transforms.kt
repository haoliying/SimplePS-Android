package com.simpleps.android.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.max

/** 常见图片变换：缩放、旋转、裁剪、翻转。 */
object Transforms {

    /** 按比例缩放。 */
    fun scale(src: Bitmap, factor: Float): Bitmap {
        if (factor <= 0f || factor == 1f) return src.copy(Bitmap.Config.ARGB_8888, true)
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * factor).toInt()),
            max(1, (src.height * factor).toInt()),
            true,
        )
    }

    /** 按目标尺寸缩放（保持比例，targetW/targetH 传 -1 表示自动）。 */
    fun resize(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val (w, h) = when {
            targetW > 0 && targetH > 0 -> targetW to targetH
            targetW > 0 -> targetW to max(1, (src.height.toFloat() * targetW / src.width).toInt())
            targetH > 0 -> max(1, (src.width.toFloat() * targetH / src.height).toInt()) to targetH
            else -> src.width to src.height
        }
        return Bitmap.createScaledBitmap(src, max(1, w), max(1, h), true)
    }

    /** 任意角度旋转（逆时针为正，expand=true 扩展画布避免裁切）。 */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (abs(degrees % 360f) < 0.01f) return src.copy(Bitmap.Config.ARGB_8888, true)
        val m = Matrix().apply { postRotate(degrees) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    /** 90 度步进旋转，times 可为负（顺时针）。 */
    fun rotate90(src: Bitmap, times: Int): Bitmap {
        val m = Matrix().apply { postRotate(90f * times) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** 裁剪，rect 会被约束到图片范围内。 */
    fun crop(src: Bitmap, rect: android.graphics.Rect): Bitmap {
        val l = rect.left.coerceIn(0, src.width - 1)
        val t = rect.top.coerceIn(0, src.height - 1)
        val r = rect.right.coerceIn(l + 1, src.width)
        val b = rect.bottom.coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /** 镜像翻转。 */
    fun flip(src: Bitmap, horizontal: Boolean): Bitmap {
        val m = Matrix()
        if (horizontal) {
            m.postScale(-1f, 1f)
            m.postTranslate(src.width.toFloat(), 0f)
        } else {
            m.postScale(1f, -1f)
            m.postTranslate(0f, src.height.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** 将文字图层合成到底图指定中心点。 */
    fun compose(base: Bitmap, layer: Bitmap, cx: Int, cy: Int): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val left = cx - layer.width / 2
        val top = cy - layer.height / 2
        c.drawBitmap(layer, left.toFloat(), top.toFloat(), null)
        return out
    }
}
