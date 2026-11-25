package com.simpleps.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.simpleps.android.image.FontRenderer
import com.simpleps.android.image.ImageProcessor
import com.simpleps.android.image.TextStyle
import com.simpleps.android.image.TextStyleAnalyzer
import com.simpleps.android.image.Transforms
import com.simpleps.android.ui.OverlayView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var info: TextView
    private var current: Bitmap? = null
    private val history = mutableListOf<Bitmap>()
    private var hidx = -1
    private var fonts: List<FontRenderer.FontInfo> = emptyList()
    private var analyzedStyle: TextStyle? = null
    private var sampleRect: Rect? = null
    private var placePoint: Pair<Int, Int>? = null
    private var currentTab = "bg"
    private lateinit var panel: LinearLayout
    private lateinit var tabButtons: MutableMap<String, LinearLayout>
    /** 当前矩形框选完成后要执行的动作（裁剪 / 抠图 / 文字采样）。 */
    private var rectAction: ((Rect) -> Unit)? = null

    // 控件引用
    private lateinit var toleranceBar: SeekBar
    private lateinit var brushBar: SeekBar
    private lateinit var refEdit: EditText
    private lateinit var textEdit: EditText
    private lateinit var fontSpinner: Spinner
    private lateinit var sizeEdit: EditText
    private lateinit var angleEdit: EditText
    private lateinit var styleInfo: TextView
    private lateinit var rotateBar: SeekBar
    private lateinit var scaleBar: SeekBar

    // "极光"主题配色：深空蓝紫背景 + 青紫粉强调
    private val BG = Color.rgb(10, 15, 34)
    private val HEADER_TOP = Color.rgb(12, 19, 48)
    private val HEADER_BOT = Color.rgb(36, 26, 69)
    private val PANEL_BG = Color.rgb(13, 19, 41)
    private val PANEL2 = Color.rgb(34, 46, 87)
    private val CARD = Color.rgb(24, 33, 66)
    private val FG = Color.rgb(238, 242, 255)
    private val MUTED = Color.rgb(154, 166, 206)
    private val ACCENT = Color.rgb(139, 92, 246)   // 紫
    private val ACCENT_CYAN = Color.rgb(34, 211, 238) // 青
    private val ACCENT2 = Color.rgb(52, 211, 153)  // 绿
    private val ROSE = Color.rgb(244, 114, 182)    // 粉

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    /** 渐变背景 drawable（可带圆角）。 */
    private fun gradientBg(top: Int, bottom: Int, radius: Float = 0f): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
        ).apply { if (radius > 0f) cornerRadius = radius }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        @Suppress("DEPRECATION")
        window.statusBarColor = HEADER_TOP

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        setContentView(root)

        // 顶部操作栏（极光渐变：品牌标识 + 撤销/重做 + 保存）
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = gradientBg(HEADER_TOP, HEADER_BOT)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "◈"; textSize = 24f; setTextColor(ACCENT_CYAN)
            setPadding(0, 0, dp(6), 0)
        })
        header.addView(TextView(this).apply {
            text = "SimplePS"
            textSize = 18f; setTextColor(FG)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "  极光编辑器"; textSize = 11f; setTextColor(MUTED)
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        header.addView(iconTextButton("↶", 20f) { undo() })
        header.addView(iconTextButton("↷", 20f) { redo() })
        header.addView(TextView(this).apply {
            text = "保存"; textSize = 13f; setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = gradientBg(ACCENT, ROSE, dp(12).toFloat())
            setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { saveCurrent() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 中央画布
        overlay = OverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(overlay)

        // 状态栏
        status = TextView(this).apply {
            text = "准备就绪 · 选择图片或点击下方工具"
            setTextColor(MUTED); textSize = 12f
            setPadding(dp(16), dp(4), dp(16), dp(2))
        }
        info = TextView(this).apply {
            setTextColor(ACCENT2); textSize = 11f; gravity = Gravity.END
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(status)
        root.addView(info)

        // 工具面板（极光玻璃卡片，可滚动）
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panelScroll = ScrollView(this).apply {
            setBackgroundColor(PANEL_BG)
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(panelScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))

        // 底部图标工具栏（悬浮胶囊：图标 + 文字，选中青色高亮）
        val navScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(HEADER_BOT)
            isHorizontalScrollBarEnabled = false
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = gradientBg(CARD, PANEL2, dp(24).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        tabButtons = LinkedHashMap()
        // 打开按钮固定在最左
        nav.addView(navItem("⊕", "打开") { pickImage.launch("image/*") },
            LinearLayout.LayoutParams(dp(66), dp(58)))
        nav.addView(View(this).apply { setBackgroundColor(PANEL2) },
            LinearLayout.LayoutParams(2, dp(36)).apply {
                leftMargin = dp(6); rightMargin = dp(4); topMargin = dp(10); bottomMargin = dp(10)
            })
        val tools = listOf(
            "bg" to Triple("◐", "去背景", {}),
            "cutout" to Triple("✂", "抠图", {}),
            "text" to Triple("T", "文字", {}),
            "crop" to Triple("▢", "裁剪", {}),
            "rotate" to Triple("⟳", "旋转", {}),
            "transform" to Triple("⤢", "变换", {}),
        )
        for ((key, t) in tools) {
            val item = navItem(t.first, t.second) { switchTab(key) }
            tabButtons[key] = item
            nav.addView(item, LinearLayout.LayoutParams(dp(66), dp(58)))
        }
        navScroll.addView(nav, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(navScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(10)) })

        // 无图片时点击画布直接选图
        overlay.onEmptyClick = { pickImage.launch("image/*") }

        fonts = FontRenderer.availableFonts()
        switchTab("bg")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun flatButton(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f
        setTextColor(FG)
        background = gradientBg(PANEL2, CARD, dp(12).toFloat())
        setOnClickListener { click() }
        isAllCaps = false
    }

    private fun iconTextButton(icon: String, size: Float, click: () -> Unit): TextView =
        TextView(this).apply {
            text = icon; textSize = size; setTextColor(FG)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { click() }
        }

    /** 底部工具栏图标按钮：图标在上、文字在下。 */
    private fun navItem(icon: String, label: String, click: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { click() }
        }
        val ic = TextView(this).apply {
            text = icon; textSize = 20f; setTextColor(MUTED); gravity = Gravity.CENTER
        }
        val lb = TextView(this).apply {
            text = label; textSize = 11f; setTextColor(MUTED); gravity = Gravity.CENTER
        }
        box.addView(ic)
        box.addView(lb)
        box.tag = Pair(ic, lb)
        return box
    }

    @Suppress("UNCHECKED_CAST")
    private fun setNavActive(item: LinearLayout, active: Boolean) {
        val color = if (active) ACCENT_CYAN else MUTED
        val pair = item.tag as? Pair<TextView, TextView> ?: return
        val ic = pair.first
        val lb = pair.second
        ic.setTextColor(color); lb.setTextColor(color)
        lb.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        item.background = if (active) gradientBg(CARD, PANEL2, dp(16).toFloat()) else null
    }

    // ------------------------------------------------------------ 面板构建
    private fun switchTab(key: String) {
        currentTab = key
        for ((k, b) in tabButtons) setNavActive(b, k == key)
        panel.removeAllViews()
        overlay.mode = OverlayView.Mode.NONE
        rectAction = null
        overlay.onRectCommitted = { r -> rectAction?.invoke(r) }
        overlay.onStrokeCommitted = null
        when (key) {
            "bg" -> buildBgPanel()
            "cutout" -> buildCutoutPanel()
            "text" -> buildTextPanel()
            "crop" -> buildCropPanel()
            "rotate" -> buildRotatePanel()
            "transform" -> buildTransformPanel()
        }
    }

    private fun sectionTitle(text: String) {
        panel.addView(TextView(this).apply {
            this.text = text
            setTextColor(ACCENT_CYAN); textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(2))
        })
    }

    private fun hint(text: String) {
        panel.addView(TextView(this).apply {
            this.text = text
            setTextColor(MUTED); textSize = 11f
            setPadding(dp(14), 0, dp(14), dp(4))
        })
    }

    private fun actionRow(vararg items: Pair<String, () -> Unit>) {
        val row = HorizontalScrollView(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, click) in items) {
            inner.addView(flatButton(label, click), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        row.addView(inner)
        panel.addView(row)
    }

    private fun labeledSeek(label: String, max: Int, def: Int,
                            onchange: ((Int) -> Unit)? = null,
                            onStop: ((Int) -> Unit)? = null): SeekBar {
        panel.addView(TextView(this).apply {
            text = label; setTextColor(MUTED); textSize = 11f
            setPadding(dp(14), dp(4), dp(14), 0)
        })
        val bar = SeekBar(this).apply {
            this.max = max; progress = def
            setPadding(dp(14), 0, dp(14), 0)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { onchange?.invoke(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onStop?.invoke(bar.progress) }
        })
        panel.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    private fun editText(hint: String, value: String, singleLine: Boolean = true): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            isSingleLine = singleLine
            setTextColor(FG)
            setHintTextColor(MUTED)
            setBackgroundColor(PANEL2)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun buildBgPanel() {
        sectionTitle("背景去除")
        toleranceBar = labeledSeek("颜色容差", 80, 45)
        hint("纯色背景用「颜色去背景」，复杂背景用「自动去背景」。")
        actionRow(
            "自动去背景" to { autoBackground() },
            "颜色去背景" to { colorBackground() },
            "清除标记" to { overlay.clearMarks(); setStatus("已清除标记") },
        )
    }

    private fun buildCutoutPanel() {
        sectionTitle("智能抠图 · 松开即生效")
        brushBar = labeledSeek("笔刷半径", 60, 24) { overlay.brushRadius = (it + 8).toFloat() }
        overlay.brushRadius = (brushBar.progress + 8).toFloat()
        hint("框选或涂抹后松开手指即刻抠图，无需点应用。")
        // 矩形框选松开即抠图
        rectAction = { r -> overlay.rect = r; applyCutout() }
        // 笔刷涂抹松开即抠图
        overlay.onStrokeCommitted = { applyCutout() }
        actionRow(
            "矩形框选" to { overlay.mode = OverlayView.Mode.RECT; setStatus("拖拽框住主体，松开即抠图") },
            "前景笔刷" to { overlay.mode = OverlayView.Mode.FG; setStatus("绿色保留：涂抹后松开即抠图") },
            "背景笔刷" to { overlay.mode = OverlayView.Mode.BG; setStatus("红色擦除：涂抹后松开即抠图") },
            "清除标记" to { overlay.clearMarks(); setStatus("已清除标记") },
        )
    }

    private fun buildTextPanel() {
        sectionTitle("字体模仿 · 从图片采样")
        hint("① 框选图中一行文字，输入它的内容，点「分析样式」。")
        actionRow("▣ 框选参考文字" to {
            overlay.mode = OverlayView.Mode.RECT
            overlay.onRectChanged = { r -> sampleRect = r }
            setStatus("在图片上拖拽框住参考文字")
        })
        refEdit = editText("图中文字内容，例如：参考文字", "参考文字")
        panel.addView(refEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })
        actionRow("🔍 分析样式" to { analyzeStyle() })

        sectionTitle("识别结果（可手动调整）")
        fontSpinner = Spinner(this)
        fontSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            fonts.map { it.name })
        panel.addView(fontSpinner)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        sizeEdit = editText("字号", "88").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        angleEdit = editText("倾斜°", "0").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
        row.addView(sizeEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(angleEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        styleInfo = TextView(this).apply {
            text = "尚未分析"; setTextColor(MUTED); textSize = 11f
            setPadding(dp(14), dp(4), dp(14), 0)
        }
        panel.addView(styleInfo)

        sectionTitle("② 输入新文字并放置")
        textEdit = editText("输入要生成的文字", "新文字")
        panel.addView(textEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })
        actionRow(
            "◎ 点击选位置" to {
                overlay.mode = OverlayView.Mode.PLACE
                overlay.onPlace = { x, y -> placePoint = x to y; setStatus("放置位置：$x,$y") }
                setStatus("在图片上点击选择文字放置位置")
            },
            "T 合成文字" to { generateText() },
        )
    }

    private fun buildTransformPanel() {
        sectionTitle("缩放 / 镜像")
        scaleBar = labeledSeek("缩放比例 (50% ~ 300%)", 250, 100,
            { setStatus("缩放 ${it + 50}%  ·  松开即生效") },
            { p -> applyTransform("缩放") { Transforms.scale(it, (p + 50) / 100f) } })
        actionRow(
            "⇋ 水平翻转" to { applyTransform("翻转") { Transforms.flip(it, true) } },
            "⇅ 垂直翻转" to { applyTransform("翻转") { Transforms.flip(it, false) } },
        )
    }

    private fun buildCropPanel() {
        sectionTitle("裁剪 · 松开即生效")
        hint("在画布上拖拽出矩形区域，松开手指即刻裁剪。")
        rectAction = { r -> applyTransform("裁剪") { bmp -> Transforms.crop(bmp, Rect(r)) } }
        actionRow(
            "▢ 框选裁剪区" to {
                overlay.mode = OverlayView.Mode.RECT
                setStatus("拖拽出裁剪区域，松开即裁剪")
            },
            "1:1" to { cropRatio(1f) },
            "4:3" to { cropRatio(4f / 3f) },
            "16:9" to { cropRatio(16f / 9f) },
        )
    }

    private fun cropRatio(ratio: Float) {
        val bmp = current ?: run { toast("请先选择图片"); return }
        val w = bmp.width; val h = bmp.height
        val r = if (w.toFloat() / h > ratio) {
            val nw = (h * ratio).toInt(); val x0 = (w - nw) / 2
            Rect(x0, 0, x0 + nw, h)
        } else {
            val nh = (w / ratio).toInt(); val y0 = (h - nh) / 2
            Rect(0, y0, w, y0 + nh)
        }
        applyTransform("裁剪") { Transforms.crop(it, r) }
    }

    private fun buildRotatePanel() {
        sectionTitle("旋转 · 松开即生效")
        actionRow(
            "↶ 左转 90°" to { applyTransform("旋转") { Transforms.rotate90(it, -1) } },
            "↷ 右转 90°" to { applyTransform("旋转") { Transforms.rotate90(it, 1) } },
        )
        rotateBar = labeledSeek("任意角度 (-180° ~ 180°)", 360, 180,
            { setStatus("旋转 ${it - 180}°  ·  松开即生效") },
            { p -> applyTransform("旋转") { Transforms.rotate(it, (p - 180).toFloat()) } })
    }

    // ------------------------------------------------------------ 业务逻辑
    private fun setStatus(text: String) { status.text = text }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val prepared = ImageProcessor.prepare(bmp)
            history.clear(); hidx = -1
            pushHistory(prepared)
            current = prepared
            overlay.setImage(prepared)
            info.text = "${prepared.width} x ${prepared.height}"
            setStatus("已载入 ${prepared.width} x ${prepared.height}")
        } catch (e: Exception) {
            toast("载入失败：${e.message}")
        }
    }

    private fun pushHistory(bmp: Bitmap) {
        while (history.size > hidx + 1 && history.isNotEmpty()) history.removeAt(history.lastIndex)
        history.add(bmp)
        if (history.size > 30) history.removeAt(0)
        hidx = history.size - 1
    }

    private fun undo() {
        if (hidx <= 0) { toast("没有可撤销的操作"); return }
        hidx--
        current = history[hidx]
        overlay.setResult(current!!)
        info.text = "${current!!.width} x ${current!!.height}"
        setStatus("已撤销")
    }

    private fun redo() {
        if (hidx >= history.size - 1) { toast("没有可重做的操作"); return }
        hidx++
        current = history[hidx]
        overlay.setResult(current!!)
        info.text = "${current!!.width} x ${current!!.height}"
        setStatus("已重做")
    }

    private fun process(name: String, block: (Bitmap) -> Bitmap) {
        val src = current
        if (src == null) { toast("请先选择图片"); return }
        setStatus("$name 处理中…")
        Thread {
            try {
                val out = block(src)
                runOnUiThread {
                    current = out
                    pushHistory(out)
                    overlay.setResult(out)
                    info.text = "${out.width} x ${out.height}"
                    setStatus("$name 完成")
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("$name 失败：${e.message}"); toast("$name 失败：${e.message}") }
            }
        }.start()
    }

    private fun applyTransform(name: String, block: (Bitmap) -> Bitmap) = process(name, block)

    private fun autoBackground() {
        process("自动去背景") { ImageProcessor.autoRemoveBackground(it, (toleranceBar.progress + 10).toFloat()) }
    }

    private fun colorBackground() {
        val bmp = current ?: run { toast("请先选择图片"); return }
        val w = bmp.width; val h = bmp.height
        val samples = listOf(2 to 2, w - 3 to 2, 2 to h - 3, w - 3 to h - 3,
            w / 2 to 2, w / 2 to h - 3, 2 to h / 2, w - 3 to h / 2)
        process("颜色去背景") { ImageProcessor.removeByColor(it, samples, (toleranceBar.progress + 10).toFloat()) }
    }

    private fun applyCutout() {
        if (current == null) { toast("请先选择图片"); return }
        val r = overlay.rect
        val fg = overlay.getFgMask()
        val bg = overlay.getBgMask()
        val tol = (toleranceBar.progress + 10).toFloat()
        if (overlay.mode == OverlayView.Mode.RECT && r != null) {
            process("矩形抠图") { ImageProcessor.rectCutout(it, Rect(r), tol) }
        } else {
            process("笔刷抠图") { ImageProcessor.brushCutout(it, fg, bg, tol) }
        }
    }

    private fun analyzeStyle() {
        val bmp = current ?: run { toast("请先选择图片"); return }
        val r = sampleRect ?: run { toast("请先框选参考文字区域"); return }
        setStatus("正在分析字体样式…")
        Thread {
            try {
                val result = TextStyleAnalyzer.analyze(bmp, Rect(r), refEdit.text.toString().trim())
                runOnUiThread {
                    analyzedStyle = result.style
                    val idx = fonts.indexOfFirst { it.path == result.style.fontPath }
                    if (idx >= 0) fontSpinner.setSelection(idx)
                    sizeEdit.setText(result.style.size.toInt().toString())
                    angleEdit.setText(result.style.angle.toInt().toString())
                    styleInfo.text = "${result.style.summary()} · 置信 ${String.format("%.2f", result.style.confidence)}"
                    setStatus("样式分析完成 · 输入新文字并选位置")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("分析失败：${e.message}"); setStatus("分析失败：${e.message}") }
            }
        }.start()
    }

    private fun generateText() {
        val bmp = current ?: run { toast("请先选择图片"); return }
        val text = textEdit.text.toString().trim()
        if (text.isEmpty()) { toast("请输入文字"); return }
        val pos = placePoint ?: (bmp.width / 2 to bmp.height / 2)
        val style = analyzedStyle ?: TextStyle(fontPath = fonts.getOrNull(fontSpinner.selectedItemPosition)?.path ?: "",
            fontName = "自定义", size = sizeEdit.text.toString().toFloatOrNull() ?: 88f,
            color = Color.WHITE, angle = angleEdit.text.toString().toFloatOrNull() ?: 0f)
        style.fontPath = fonts.getOrNull(fontSpinner.selectedItemPosition)?.path ?: style.fontPath
        style.fontName = fonts.getOrNull(fontSpinner.selectedItemPosition)?.name ?: style.fontName
        style.size = sizeEdit.text.toString().toFloatOrNull() ?: style.size
        style.angle = angleEdit.text.toString().toFloatOrNull() ?: style.angle
        process("文字合成") { base ->
            val layer = FontRenderer.render(
                text = text, fontPath = style.fontPath, size = style.size, color = style.color,
                rotateAngle = style.angle,
            )
            Transforms.compose(base, layer, pos.first, pos.second)
        }
    }

    private fun saveCurrent() {
        val bmp = current ?: run { toast("没有可保存的图片"); return }
        val name = "SimplePS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SimplePS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SimplePS")
                dir.mkdirs()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, File(dir, name).outputStream())
            }
            toast("已保存到相册 / Pictures/SimplePS")
            setStatus("已保存：$name")
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
        }
    }
}
