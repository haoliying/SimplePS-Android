package com.simpleps.android.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import java.util.Random

/** 系统字体扫描与文字渲染，支持渐变、描边、阴影和手写模仿。 */
object FontRenderer {

    data class FontInfo(val name: String, val path: String)

    /** 扫描常见系统字体目录；失败时返回内置字体占位项。 */
    fun availableFonts(): List<FontInfo> {
        val found = LinkedHashMap<String, FontInfo>()
        val dirs = listOf(
            File("/system/fonts"),
            File("/system/framework"),
            File("/product/fonts"),
            File("/vendor/fonts"),
        )
        for (dir in dirs) {
            val files = try {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".ttf", true) ||
                        f.name.endsWith(".otf", true) || f.name.endsWith(".ttc", true) }
            } catch (e: Exception) {
                null
            } ?: continue
            for (f in files) {
                found.putIfAbsent(f.nameWithoutExtension, FontInfo(f.nameWithoutExtension, f.absolutePath))
            }
        }
        if (found.isEmpty()) {
            found["系统默认"] = FontInfo("系统默认", "")
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun typeface(path: String?): Typeface = try {
        if (path.isNullOrEmpty()) Typeface.DEFAULT else Typeface.createFromFile(path)
    } catch (e: Exception) {
        Typeface.DEFAULT
    }

    /** 检测字体能否渲染 text 中的字形；中文用「两个不同汉字渲染是否相同」判断。 */
    fun fontHasChars(path: String?, text: String): Boolean {
        if (path.isNullOrEmpty()) return true
        val chars = text.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return true
        val tf = try { Typeface.createFromFile(path) } catch (e: Exception) { return false }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = 32f }
        fun sig(c: String): Pair<Int, Int> {
            val w = max(2, paint.measureText(c).toInt())
            val h = 48
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            cv.drawText(c, 0f, 32f, paint)
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            var hash = 0; var count = 0
            for (v in px) { hash = hash * 31 + Color.alpha(v); if (Color.alpha(v) > 0) count++ }
            return hash to count
        }
        val hanChars = chars.filter { it.code in 0x4e00..0x9fff }
        if (hanChars.length >= 2 && hanChars[0] != hanChars[1]) {
            if (sig(hanChars[0].toString()) == sig(hanChars[1].toString())) return false
        }
        return true
    }

    /**
     * 渲染文字为透明背景位图。
     */
    fun render(
        text: String,
        fontPath: String?,
        size: Float,
        color: Int,
        strokeWidth: Float = 0f,
        strokeColor: Int = Color.BLACK,
        gradient: IntArray? = null,
        handwriting: Boolean = false,
        jitter: Float = 4f,
        angle: Float = 8f,
        seed: Int = 0,
        padding: Int = 30,
        rotateAngle: Float = 0f,
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(fontPath)
            textSize = max(12f, size)
            this.color = color
            isSubpixelText = true
        }

        val lines = text.split("\n")
        val widths = lines.map { max(1f, paint.measureText(it)) }
        val fm = paint.fontMetrics
        val lineH = ceil(fm.descent - fm.ascent) + size * 0.3f
        val pad = padding.coerceAtLeast(1)
        val w = ceil(widths.maxOrNull() ?: 100f).toInt() + pad * 2
        val h = ceil(lineH * lines.size).toInt() + pad * 2
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (gradient != null && gradient.size >= 2) {
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                gradient[0], gradient[1], Shader.TileMode.CLAMP,
            )
        }

        val baseY = pad.toFloat() - fm.ascent

        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = strokeColor
            if (!handwriting) {
                lines.forEachIndexed { i, line -> canvas.drawText(line, pad.toFloat(), baseY + i * lineH, paint) }
            } else {
                drawHandwriting(canvas, lines, paint, pad.toFloat(), baseY, lineH, jitter, angle, seed)
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = color
        paint.setShadowLayer(size * 0.06f, size * 0.035f, size * 0.035f, Color.argb(110, 0, 0, 0))
        if (!handwriting) {
            lines.forEachIndexed { i, line -> canvas.drawText(line, pad.toFloat(), baseY + i * lineH, paint) }
        } else {
            drawHandwriting(canvas, lines, paint, pad.toFloat(), baseY, lineH, jitter, angle, seed)
        }
        paint.clearShadowLayer()

        if (kotlin.math.abs(rotateAngle) > 0.01f) {
            val m = android.graphics.Matrix().apply {
                postRotate(rotateAngle, w / 2f, h / 2f)
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
            bmp.recycle()
            return rotated
        }
        return bmp
    }

    private fun drawHandwriting(
        canvas: Canvas,
        lines: List<String>,
        paint: Paint,
        startX: Float,
        startY: Float,
        lineH: Float,
        jitter: Float,
        angle: Float,
        seed: Int,
    ) {
        val rnd = Random(seed.toLong())
        var y = startY
        for (line in lines) {
            var x = startX
            for (ch in line) {
                val cw = max(4f, paint.measureText(ch.toString()))
                canvas.save()
                canvas.translate(
                    x + (rnd.nextFloat() * 2f - 1f) * jitter,
                    y + (rnd.nextFloat() * 2f - 1f) * jitter,
                )
                canvas.rotate(rnd.nextFloat() * 2f * angle - angle)
                canvas.drawText(ch.toString(), 0f, 0f, paint)
                canvas.restore()
                x += cw * 1.06f
            }
            y += lineH
        }
    }
}
