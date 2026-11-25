package com.simpleps.android.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 图像处理：颜色去背景、自动去背景、矩形抠图、笔刷抠图。
 * 不依赖 OpenCV，可离线运行；处理前建议先调用 [prepare] 限制分辨率。
 */
object ImageProcessor {

    private const val MAX_SIDE = 1400
    /** 去背景算法的计算边长：在缩略图上算 alpha 再放大，显著提速。 */
    private const val WORK_SIDE = 900

    /** 限制图片边长，避免大图导致内存与速度问题。 */
    fun prepare(src: Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= MAX_SIDE) return src.copy(Bitmap.Config.ARGB_8888, true)
        val s = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * s).toInt()),
            max(1, (src.height * s).toInt()),
            true,
        )
    }

    /**
     * 在受限边长的缩略图上运行耗时算法，再把结果放大回原尺寸。
     * 颜色去背景/自动去背景的 alpha 边缘经双线性放大后依然自然，
     * 但计算量从 O(MAX_SIDE^2) 降到 O(WORK_SIDE^2)，通常快 2 倍以上。
     */
    private fun onWorkSize(src: Bitmap, block: (Bitmap) -> Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= WORK_SIDE) return block(src)
        val s = WORK_SIDE.toFloat() / m
        val small = Bitmap.createScaledBitmap(
            src, max(1, (src.width * s).toInt()), max(1, (src.height * s).toInt()), true)
        val out = block(small)
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }

    private fun distance(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int): Float {
        val dr = (r1 - r2).toFloat()
        val dg = (g1 - g2).toFloat()
        val db = (b1 - b2).toFloat()
        return sqrt(2f * dr * dr + 4f * dg * dg + 3f * db * db)
    }

    private fun alphaFromDistance(d: Float, tolerance: Float, softness: Float): Int {
        val lo = max(0f, tolerance - softness)
        val hi = tolerance + softness
        val t = ((d - lo) / (hi - lo)).coerceIn(0f, 1f)
        return (255f * t * t * (3f - 2f * t)).toInt()
    }

    /** 3x3 均值羽化，让 alpha 边缘更柔和。 */
    private fun feather(alpha: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(alpha.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        sum += alpha[yy * w + xx]
                        n++
                    }
                }
                out[y * w + x] = sum / n
            }
        }
        return out
    }

    private fun compose(src: Bitmap, alpha: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            px[i] = (alpha[i] shl 24) or (px[i] and 0x00FFFFFF)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px
    }

    private fun colorOf(c: Int): IntArray = intArrayOf(Color.red(c), Color.green(c), Color.blue(c))

    /** 按采样颜色去背景，适合纯色 / 渐变背景（缩略图加速）。 */
    fun removeByColor(
        src: Bitmap,
        samples: List<Pair<Int, Int>>,
        tolerance: Float = 45f,
        softness: Float = 14f,
    ): Bitmap = onWorkSize(src) { bmp ->
        val w = bmp.width
        val h = bmp.height
        val px = pixels(bmp)
        // 采样点来自原图坐标，按比例映射到缩略图
        val sx = w.toFloat() / src.width
        val sy = h.toFloat() / src.height
        val mappedSamples = samples.map { (x, y) -> (x * sx).toInt() to (y * sy).toInt() }
        val cols = mappedSamples.map { (x, y) ->
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            colorOf(px[yy * w + xx])
        }
        if (cols.isEmpty()) return@onWorkSize bmp.copy(Bitmap.Config.ARGB_8888, true)

        val alpha = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = colorOf(px[i])
                var d = Float.MAX_VALUE
                for (col in cols) {
                    d = min(d, distance(c[0], c[1], c[2], col[0], col[1], col[2]))
                }
                alpha[i] = alphaFromDistance(d, tolerance, softness)
            }
        }
        compose(bmp, feather(alpha, w, h))
    }

    /** 自动去背景：边缘颜色聚类 + 从边框区域生长（缩略图加速）。 */
    fun autoRemoveBackground(src: Bitmap, tolerance: Float = 48f): Bitmap = onWorkSize(src) { bmp ->
        val w = bmp.width
        val h = bmp.height
        val px = pixels(bmp)

        // 边缘像素量化统计，取最常见的 4 种作为背景色
        val step = max(1, min(w, h) / 100)
        val bins = HashMap<Int, Int>()
        fun add(c: Int) {
            val key = ((Color.red(c) shr 3) shl 10) or ((Color.green(c) shr 3) shl 5) or (Color.blue(c) shr 3)
            bins[key] = (bins[key] ?: 0) + 1
        }
        for (x in 0 until w step step) {
            add(px[x]); add(px[(h - 1) * w + x])
        }
        for (y in 0 until h step step) {
            add(px[y * w]); add(px[y * w + w - 1])
        }
        val centers = bins.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { (key, _) ->
                intArrayOf(
                    ((key shr 10) and 31) * 8 + 4,
                    ((key shr 5) and 31) * 8 + 4,
                    (key and 31) * 8 + 4,
                )
            }
        if (centers.isEmpty()) return@onWorkSize bmp.copy(Bitmap.Config.ARGB_8888, true)

        val dist = FloatArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = colorOf(px[i])
                var d = Float.MAX_VALUE
                for (center in centers) {
                    d = min(d, distance(c[0], c[1], c[2], center[0], center[1], center[2]))
                }
                dist[i] = d
            }
        }

        // 从四边开始，把相连且颜色接近背景的区域标记为背景
        val bg = BooleanArray(px.size)
        val queue = ArrayDeque<Int>()
        val limit = tolerance * 1.2f
        fun tryAdd(i: Int) {
            if (!bg[i] && dist[i] < limit) {
                bg[i] = true
                queue.addLast(i)
            }
        }
        for (x in 0 until w) { tryAdd(x); tryAdd((h - 1) * w + x) }
        for (y in 0 until h) { tryAdd(y * w); tryAdd(y * w + w - 1) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w
            val y = i / w
            if (x > 0) tryAdd(i - 1)
            if (x < w - 1) tryAdd(i + 1)
            if (y > 0) tryAdd(i - w)
            if (y < h - 1) tryAdd(i + w)
        }

        val alpha = IntArray(px.size) { if (bg[it]) 0 else 255 }
        compose(bmp, feather(alpha, w, h))
    }

    /** 矩形抠图：框外为背景，框内按与框外/框内颜色相似度分类。 */
    fun rectCutout(src: Bitmap, rect: Rect, tolerance: Float = 50f): Bitmap {
        val bmp = src
        val w = bmp.width
        val h = bmp.height
        val px = pixels(bmp)
        val l = rect.left.coerceIn(0, w - 1)
        val t = rect.top.coerceIn(0, h - 1)
        val r = rect.right.coerceIn(l + 1, w - 1)
        val b = rect.bottom.coerceIn(t + 1, h - 1)

        val bgColors = ArrayList<IntArray>()
        val fgColors = ArrayList<IntArray>()
        val ringStep = max(1, min(r - l, b - t) / 20)
        for (x in max(0, l - 20)..min(w - 1, r + 20) step ringStep) {
            if (x !in l..r) {
                bgColors.add(colorOf(px[t * w + x]))
                bgColors.add(colorOf(px[b * w + x]))
            }
        }
        for (y in max(0, t - 20)..min(h - 1, b + 20) step ringStep) {
            if (y !in t..b) {
                bgColors.add(colorOf(px[y * w + l]))
                bgColors.add(colorOf(px[y * w + r]))
            }
        }
        val cx0 = l + (r - l) / 3
        val cx1 = l + (r - l) * 2 / 3
        val cy0 = t + (b - t) / 3
        val cy1 = t + (b - t) * 2 / 3
        for (y in cy0..cy1 step max(1, (cy1 - cy0) / 12)) {
            for (x in cx0..cx1 step max(1, (cx1 - cx0) / 12)) {
                fgColors.add(colorOf(px[y * w + x]))
            }
        }
        if (bgColors.isEmpty()) bgColors.add(intArrayOf(255, 255, 255))
        if (fgColors.isEmpty()) fgColors.add(colorOf(px[((t + b) / 2) * w + (l + r) / 2]))

        val alpha = IntArray(px.size)
        for (y in t..b) {
            for (x in l..r) {
                val i = y * w + x
                val c = colorOf(px[i])
                var dbg = Float.MAX_VALUE
                var dfg = Float.MAX_VALUE
                for (col in bgColors) dbg = min(dbg, distance(c[0], c[1], c[2], col[0], col[1], col[2]))
                for (col in fgColors) dfg = min(dfg, distance(c[0], c[1], c[2], col[0], col[1], col[2]))
                alpha[i] = if (dfg <= dbg + tolerance * 0.2f) 255 else 0
            }
        }
        return compose(bmp, feather(alpha, w, h))
    }

    /** 笔刷抠图：根据前景/背景笔刷采样到的颜色分布分类其余像素。 */
    fun brushCutout(src: Bitmap, fgMask: Bitmap?, bgMask: Bitmap?, tolerance: Float = 50f): Bitmap {
        val bmp = src
        val w = bmp.width
        val h = bmp.height
        val px = pixels(bmp)
        val fgPx = fgMask?.let { pixels(it) }
        val bgPx = bgMask?.let { pixels(it) }

        val fgColors = ArrayList<IntArray>()
        val bgColors = ArrayList<IntArray>()
        val sampleStep = max(1, (w * h) / 3000)
        if (fgPx != null) {
            for (i in px.indices step sampleStep) {
                if (Color.alpha(fgPx[i]) > 0) fgColors.add(colorOf(px[i]))
            }
        }
        if (bgPx != null) {
            for (i in px.indices step sampleStep) {
                if (Color.alpha(bgPx[i]) > 0) bgColors.add(colorOf(px[i]))
            }
        }
        if (fgColors.isEmpty()) {
            fgColors.add(colorOf(px[(h / 2) * w + w / 2]))
        }
        if (bgColors.isEmpty()) {
            bgColors.add(colorOf(px[0])); bgColors.add(colorOf(px[w - 1]))
            bgColors.add(colorOf(px[(h - 1) * w])); bgColors.add(colorOf(px[(h - 1) * w + w - 1]))
        }

        val alpha = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = colorOf(px[i])
                var dbg = Float.MAX_VALUE
                var dfg = Float.MAX_VALUE
                for (col in fgColors) dfg = min(dfg, distance(c[0], c[1], c[2], col[0], col[1], col[2]))
                for (col in bgColors) dbg = min(dbg, distance(c[0], c[1], c[2], col[0], col[1], col[2]))
                alpha[i] = if (dfg <= dbg + tolerance * 0.2f) 255 else 0
            }
        }
        fgPx?.let { mask -> for (i in px.indices) if (Color.alpha(mask[i]) > 0) alpha[i] = 255 }
        bgPx?.let { mask -> for (i in px.indices) if (Color.alpha(mask[i]) > 0) alpha[i] = 0 }

        return compose(bmp, feather(alpha, w, h))
    }
}
