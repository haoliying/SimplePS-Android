package com.simpleps.android.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 从图片中的参考文字区域分析出的样式。 */
data class TextStyle(
    var fontPath: String = "",
    var fontName: String = "",
    var size: Float = 88f,
    var color: Int = Color.rgb(30, 30, 30),
    var angle: Float = 0f,
    var confidence: Float = 0f,
) {
    fun summary(): String = "字体 $fontName · 字号 ${size.toInt()} · 倾斜 ${angle.toInt()}°"
}

/**
 * 纯 Kotlin 文字样式分析：颜色 / 字号 / 倾斜角 / 字体匹配。
 * 无需 OCR；若提供 referenceText（图中文字内容），字体匹配更准确。
 */
object TextStyleAnalyzer {

    data class Result(val style: TextStyle)

    /** 分析 rect 区域，referenceText 为图中那句话的内容（可选）。 */
    fun analyze(src: Bitmap, rect: Rect, referenceText: String?): Result {
        val l = rect.left.coerceIn(0, src.width - 1)
        val t = rect.top.coerceIn(0, src.height - 1)
        val r = rect.right.coerceIn(l + 1, src.width)
        val b = rect.bottom.coerceIn(t + 1, src.height)
        val w = r - l
        val h = b - t
        if (w < 6 || h < 6) throw IllegalArgumentException("参考区域太小")

        val crop = Bitmap.createBitmap(src, l, t, w, h)
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)

        // 1. 背景色：四边像素量化后取众数
        val bins = HashMap<Int, Int>()
        fun add(c: Int) {
            val key = ((Color.red(c) shr 4) shl 8) or ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
            bins[key] = (bins[key] ?: 0) + 1
        }
        for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        val bgKey = bins.entries.maxByOrNull { it.value }?.key ?: 0xffff
        val bg = intArrayOf(
            ((bgKey shr 8) and 15) * 16 + 8,
            ((bgKey shr 4) and 15) * 16 + 8,
            (bgKey and 15) * 16 + 8,
        )

        // 2. 前景蒙版：与背景色距离超阈值的像素
        fun dist(c: Int): Float {
            val dr = (Color.red(c) - bg[0]).toFloat()
            val dg = (Color.green(c) - bg[1]).toFloat()
            val db = (Color.blue(c) - bg[2]).toFloat()
            return sqrt(2f * dr * dr + 4f * dg * dg + 3f * db * db)
        }
        val dists = FloatArray(px.size) { dist(px[it]) }
        val sorted = dists.clone().also { it.sort() }
        val tol = max(18f, sorted[(sorted.size * 0.88f).toInt().coerceIn(0, sorted.size - 1)])
        val mask = BooleanArray(px.size) { dists[it] > tol }

        // 行列投影过滤噪点
        val rowCount = IntArray(h); val colCount = IntArray(w)
        var fgCount = 0
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            rowCount[y]++; colCount[x]++; fgCount++
        }
        if (fgCount == 0) { for (i in mask.indices) mask[i] = dists[i] > max(12f, sorted[(sorted.size * 0.96f).toInt()]) }
        val rth = max(1, (rowCount.maxOrNull() ?: 0) / 16)
        val cth = max(1, (colCount.maxOrNull() ?: 0) / 16)
        for (y in 0 until h) for (x in 0 until w) if (rowCount[y] < rth || colCount[x] < cth) mask[y * w + x] = false

        // 3. 文字颜色：前景像素中位数
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (i in px.indices) if (mask[i]) {
            sr += Color.red(px[i]); sg += Color.green(px[i]); sb += Color.blue(px[i]); n++
        }
        val textColor = if (n > 0) Color.rgb(sr / n, sg / n, sb / n) else Color.rgb(30, 30, 30)

        // 4. 字号：最长连续活跃行段作为行高
        var best = 0; var cur = 0; var prev = -2
        for (y in 0 until h) {
            if (rowCount[y] >= rth) {
                cur = if (prev == y - 1) cur + 1 else 1
                if (cur > best) best = cur
                prev = y
            }
        }
        val lineH = if (best > 0) best else h
        val size = (lineH / 0.9f).coerceIn(12f, 400f)

        // 5. 倾斜角：前景像素主成分方向
        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        var angle = 0f
        if (maxX > minX && maxY >= minY) {
            val pts = ArrayList<Pair<Float, Float>>()
            val step = max(1, (w * h) / 3000)
            var idx = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (idx++ % step == 0 && mask[y * w + x]) pts.add(x.toFloat() to y.toFloat())
            }
            if (pts.size >= 12) {
                val mx = pts.sumOf { it.first.toDouble() } / pts.size
                val my = pts.sumOf { it.second.toDouble() } / pts.size
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for ((x, y) in pts) {
                    val dx = x.toDouble() - mx; val dy = y.toDouble() - my
                    sxx += dx * dx; syy += dy * dy; sxy += dx * dy
                }
                val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
                var deg = Math.toDegrees(theta).toFloat()
                while (deg >= 90) deg -= 180
                while (deg < -90) deg += 180
                if (kotlin.math.abs(deg) <= 35f) angle = deg
            }
        }

        // 6. 字体匹配
        val style = TextStyle(fontPath = "", fontName = "系统默认", size = size,
                              color = textColor, angle = angle)
        val ref = referenceText?.trim()
        if (!ref.isNullOrEmpty()) {
            val fonts = FontRenderer.availableFonts()
            val target = tightMask(crop, mask, w, h)
            var bestScore = -1f
            for (f in fonts) {
                if (!FontRenderer.fontHasChars(f.path, ref)) continue
                try {
                    val layer = FontRenderer.render(ref, f.path, 64f, Color.BLACK, padding = 6)
                    val lm = alphaMask(layer)
                    val score = iou(tightBitmapMask(lm), target)
                    if (score > bestScore) {
                        bestScore = score
                        style.fontPath = f.path
                        style.fontName = f.name
                    }
                } catch (_: Exception) {}
            }
            style.confidence = if (bestScore < 0f) 0f else bestScore
        }
        if (style.fontPath.isEmpty()) {
            val fonts = FontRenderer.availableFonts()
            val cjk = ref?.any { it.code in 0x4e00..0x9fff } == true
            val pick = if (cjk) fonts.firstOrNull { it.name.contains("Noto", true) || it.name.contains("CJK", true) }
                ?: fonts.firstOrNull() else fonts.firstOrNull()
            style.fontPath = pick?.path ?: ""
            style.fontName = pick?.name ?: "系统默认"
        }
        return Result(style)
    }

    // ---- 二值蒙版工具（ mask 用 (w,h) 描述，紧凑存储 w*h ） ----
    private data class BinMask(val w: Int, val h: Int, val data: BooleanArray)

    private fun alphaMask(bmp: Bitmap): BinMask {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return BinMask(w, h, BooleanArray(px.size) { Color.alpha(px[it]) > 100 })
    }

    /** 从 (w,h) 蒙版裁掉空白边距。 */
    private fun tight(m: BinMask): BinMask {
        var minX = m.w; var maxX = -1; var minY = m.h; var maxY = -1
        for (y in 0 until m.h) for (x in 0 until m.w) if (m.data[y * m.w + x]) {
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
        if (maxX < 0) return BinMask(1, 1, BooleanArray(1))
        val nw = maxX - minX + 1; val nh = maxY - minY + 1
        val out = BooleanArray(nw * nh)
        for (y in minY..maxY) for (x in minX..maxX) {
            if (m.data[y * m.w + x]) out[(y - minY) * nw + (x - minX)] = true
        }
        return BinMask(nw, nh, out)
    }

    private fun tightMask(crop: Bitmap, mask: BooleanArray, w: Int, h: Int): BinMask =
        tight(BinMask(w, h, mask))

    private fun tightBitmapMask(m: BinMask): BinMask = tight(m)

    /** 把任意尺寸蒙版重采样到 48x48 网格后计算 IoU。 */
    private fun iou(a: BinMask, b: BinMask): Float {
        val N = 48
        fun grid(m: BinMask): BooleanArray {
            val g = BooleanArray(N * N)
            for (gy in 0 until N) for (gx in 0 until N) {
                val x0 = gx * m.w / N; val x1 = max(x0 + 1, (gx + 1) * m.w / N)
                val y0 = gy * m.h / N; val y1 = max(y0 + 1, (gy + 1) * m.h / N)
                var hit = false
                outer@ for (y in y0 until min(y1, m.h)) for (x in x0 until min(x1, m.w)) {
                    if (m.data[y * m.w + x]) { hit = true; break@outer }
                }
                g[gy * N + gx] = hit
            }
            return g
        }
        val ga = grid(a); val gb = grid(b)
        var inter = 0; var union = 0
        for (i in ga.indices) {
            if (ga[i] && gb[i]) inter++
            if (ga[i] || gb[i]) union++
        }
        return if (union == 0) 0f else inter.toFloat() / union
    }
}
