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
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.simpleps.android.image.FontRenderer
import com.simpleps.android.image.ImageProcessor
import com.simpleps.android.ui.OverlayView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var toleranceBar: SeekBar
    private lateinit var brushBar: SeekBar
    private lateinit var textEdit: EditText
    private lateinit var fontSpinner: Spinner
    private lateinit var styleGroup: RadioGroup
    private lateinit var gradientCheck: CheckBox
    private var fonts: List<FontRenderer.FontInfo> = emptyList()
    private var current: Bitmap? = null
    private var handId = -1

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SimplePS - 去背景 / 抠图 / 字体模仿"

        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        val header = TextView(this).apply {
            text = "SimplePS  ·  图片去背景 / 抠图 / 字体模仿"
            textSize = 19f
            setTextColor(Color.rgb(37, 99, 235))
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(header)

        val row1 = buttonRow(
            "选择图片" to { pickImage.launch("image/*") },
            "自动去背景" to { autoBackground() },
            "颜色去背景" to { colorBackground() },
            "清除标记" to { overlay.clearMarks(); setStatus("已清除标记") },
        )
        root.addView(row1)

        val row2 = buttonRow(
            "矩形框选" to { overlay.mode = OverlayView.Mode.RECT; setStatus("在图片上拖拽框住主体，然后点「应用抠图」") },
            "前景笔刷" to { overlay.mode = OverlayView.Mode.FG; setStatus("绿色笔刷：涂抹需要保留的区域") },
            "背景笔刷" to { overlay.mode = OverlayView.Mode.BG; setStatus("红色笔刷：涂抹需要擦除的区域") },
            "应用抠图" to { applyCutout() },
        )
        root.addView(row2)

        overlay = OverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)).apply { topMargin = dp(6) }
        }
        root.addView(overlay)

        status = TextView(this).apply {
            text = "准备就绪"
            setTextColor(Color.rgb(90, 96, 106))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(status)

        toleranceBar = labeledSeek(root, "背景容差", 10, 90, 45)
        brushBar = labeledSeek(root, "笔刷半径", 8, 80, 24)
        overlay.brushRadius = brushBar.progress.toFloat()
        brushBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                overlay.brushRadius = p.toFloat()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        textEdit = EditText(this).apply {
            hint = "输入文字，例如：你好 SimplePS"
            setText("你好 SimplePS")
            isSingleLine = true
            textSize = 16f
        }
        root.addView(textEdit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fonts = FontRenderer.availableFonts()
        fontSpinner = Spinner(this)
        fontSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            fonts.map { it.name })
        root.addView(fontSpinner)

        styleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val normal = RadioButton(this).apply { text = "标准排版"; isChecked = true }
        val hand = RadioButton(this).apply { text = "手写模仿"; id = View.generateViewId() }
        handId = hand.id
        styleGroup.addView(normal)
        styleGroup.addView(hand)
        root.addView(styleGroup)

        gradientCheck = CheckBox(this).apply { text = "使用红蓝渐变" }
        root.addView(gradientCheck)

        val row3 = buttonRow(
            "生成文字" to { generateText() },
            "保存 PNG" to { saveCurrent() },
        )
        root.addView(row3)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, click) in items) {
            row.addView(Button(this).apply {
                text = label
                textSize = 13f
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun labeledSeek(parent: LinearLayout, label: String, min: Int, max: Int, def: Int): SeekBar {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.rgb(70, 76, 86))
            setPadding(0, dp(8), 0, 0)
        })
        val bar = SeekBar(this).apply { this.max = max - min; progress = def - min }
        // SeekBar 从 0 开始，这里用偏移还原真实值
        bar.tag = min
        parent.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return bar
    }

    private fun valueOf(bar: SeekBar): Float = (bar.progress + (bar.tag as Int)).toFloat()

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            val prepared = ImageProcessor.prepare(bmp)
            current = prepared
            overlay.setImage(prepared)
            setStatus("已载入 ${prepared.width} x ${prepared.height}")
        } catch (e: Exception) {
            toast("载入失败：${e.message}")
            setStatus("载入失败：${e.message}")
        }
    }

    private fun process(name: String, block: (Bitmap) -> Bitmap) {
        val src = current
        if (src == null) {
            toast("请先选择图片或生成文字")
            return
        }
        setStatus("$name 处理中...")
        Thread {
            try {
                val out = block(src)
                runOnUiThread {
                    current = out
                    overlay.setResult(out)
                    setStatus("$name 完成")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("$name 失败：${e.message}")
                    toast("$name 失败：${e.message}")
                }
            }
        }.start()
    }

    private fun autoBackground() {
        val tol = valueOf(toleranceBar)
        process("自动去背景") { ImageProcessor.autoRemoveBackground(it, tol) }
    }

    private fun colorBackground() {
        val bmp = current ?: run { toast("请先选择图片"); return }
        val w = bmp.width
        val h = bmp.height
        val samples = listOf(
            2 to 2, w - 3 to 2, 2 to h - 3, w - 3 to h - 3,
            w / 2 to 2, w / 2 to h - 3, 2 to h / 2, w - 3 to h / 2,
        )
        val tol = valueOf(toleranceBar)
        process("颜色去背景") { ImageProcessor.removeByColor(it, samples, tol) }
    }

    private fun applyCutout() {
        if (current == null) { toast("请先选择图片"); return }
        val r = overlay.rect
        val fg = overlay.getFgMask()
        val bg = overlay.getBgMask()
        val tol = valueOf(toleranceBar)
        when {
            overlay.mode == OverlayView.Mode.RECT && r != null -> {
                val rect = Rect(r)
                process("矩形抠图") { ImageProcessor.rectCutout(it, rect, tol) }
            }
            else -> process("笔刷抠图") { ImageProcessor.brushCutout(it, fg, bg, tol) }
        }
    }

    private fun generateText() {
        val text = textEdit.text.toString().trim()
        if (text.isEmpty()) {
            toast("请输入文字")
            return
        }
        val fontPath = fonts.getOrNull(fontSpinner.selectedItemPosition)?.path
        val handwriting = styleGroup.checkedRadioButtonId == handId
        val gradient = if (gradientCheck.isChecked) intArrayOf(Color.RED, Color.BLUE) else null
        try {
            val bmp = FontRenderer.render(
                text = text,
                fontPath = fontPath,
                size = 88f,
                color = Color.WHITE,
                strokeWidth = 3f,
                strokeColor = Color.rgb(40, 50, 70),
                gradient = gradient,
                handwriting = handwriting,
                jitter = 5f,
                angle = 9f,
                seed = 42,
            )
            current = bmp
            overlay.setResult(bmp)
            setStatus("文字已生成，可点「保存 PNG」导出透明图层")
        } catch (e: Exception) {
            toast("生成失败：${e.message}")
        }
    }

    private fun saveCurrent() {
        val bmp = current
        if (bmp == null) {
            toast("没有可保存的图片")
            return
        }
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
