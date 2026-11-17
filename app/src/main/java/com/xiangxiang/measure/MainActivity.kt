package com.xiangxiang.measure

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.Surface
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var tone: ToneGenerator? = null

    private lateinit var content: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var tagline: TextView
    private lateinit var nav: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var vialView: VialView

    private var angleX = 0f
    private var angleY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var smoothX = 0f
    private var smoothY = 0f
    private var threshold = 1.0f
    private var soundEnabled = true
    private var currentPage = "level"
    private var isLandscapeMode = false
    private var lastBeep = 0L

    private val navy = Color.rgb(8, 18, 36)
    private val green = Color.rgb(92, 255, 75)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)

        buildUI()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(
        value: String,
        size: Float,
        color: Int = white,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener { action() }
        }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(14), dp(14), dp(14), dp(8))
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            text("湘湘智能测量尺", 24f, white, true),
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )
        header.addView(button("⚙") {
            Toast.makeText(this, "设置功能后续完善", Toast.LENGTH_SHORT).show()
        })

        root.addView(header)
        tagline = text("精准测量 · 轻松生活", 13f, Color.LTGRAY)
        root.addView(tagline)

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        nav.addView(button("气泡水平尺") { exitLandscape(); showLevel() })
        nav.addView(button("横屏测量") { enterLandscape() })
        nav.addView(button("摄像头测量") { showCamera() })
        nav.addView(button("屏幕电子尺") { showRuler() })
        root.addView(nav)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(20))
        }
        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
        showLevel()
    }

    private fun panel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(17, 34, 54), 22)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

    private fun enterLandscape() {
        isLandscapeMode = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        showLevel()
    }

    private fun exitLandscape() {
        isLandscapeMode = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isLandscapeMode && currentPage == "level") {
            showLevel()
        }
    }

    private fun showLevel() {
        if (isLandscapeMode) {
            showLandscapeLevel()
        } else {
            header.visibility = View.VISIBLE
            tagline.visibility = View.VISIBLE
            nav.visibility = View.VISIBLE
            showPortraitLevel()
        }
    }

    private fun showPortraitLevel() {
        content.orientation = LinearLayout.VERTICAL
        currentPage = "level"
        content.removeAllViews()

        content.addView(
            text("真实模拟 · 双轴气泡水平尺", 19f, white, true)
        )

        statusText = text("正在读取传感器…", 20f, green, true)
        statusText.setPadding(0, dp(12), 0, dp(8))
        content.addView(statusText)

        val panel = panel()
        vialView = VialView(this)
        panel.addView(
            vialView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
            )
        )
        content.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(370)
            )
        )

        angleText = text("横向 0.0°     纵向 0.0°", 17f)
        angleText.setPadding(0, dp(14), 0, dp(10))
        content.addView(angleText)

        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        soundRow.addView(text("偏差超过阈值时滴滴提示", 14f))
        val soundSwitch = Switch(this).apply {
            isChecked = soundEnabled
            setOnCheckedChangeListener { _, checked ->
                soundEnabled = checked
                if (!checked) tone?.stopTone()
            }
        }
        soundRow.addView(soundSwitch)
        content.addView(soundRow)

        thresholdLabel = text("提示阈值：${threshold.toInt()}°", 14f, Color.LTGRAY)
        content.addView(thresholdLabel)
        val seek = SeekBar(this).apply {
            max = 4
            progress = (threshold - 0.5f).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                    threshold = 0.5f + p
                    thresholdLabel.text = "提示阈值：${"%.1f".format(threshold)}°"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        content.addView(seek)

        if (isLandscapeMode) {
            content.addView(button("返回竖屏") {
                exitLandscape()
                showLevel()
            })
        }

        content.addView(button("校准当前水平零点") {
            offsetX += angleX
            offsetY += angleY
            angleX = 0f
            angleY = 0f
            smoothX = 0f
            smoothY = 0f
            updateLevel()
            Toast.makeText(this, "校准完成", Toast.LENGTH_SHORT).show()
        })

        updateLevel()
    }

    private fun updateLevel() {
        if (currentPage != "level" || !::statusText.isInitialized) return

        val deviation = sqrt(angleX * angleX + angleY * angleY)
        val level = deviation <= threshold

        statusText.text = if (level) "✓ 已水平" else "● 尚未水平"
        statusText.setTextColor(if (level) green else Color.YELLOW)

        angleText.text = String.format(
            java.util.Locale.US,
            "横向 %.1f°       纵向 %.1f°",
            angleY, angleX
        )

        if (::vialView.isInitialized) {
            vialView.setAngles(angleY, angleX)
        }

        if (!level && soundEnabled) {
            val now = System.currentTimeMillis()
            if (now - lastBeep >= 550) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                lastBeep = now
            }
        } else {
            tone?.stopTone()
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        tone?.stopTone()
    }

    override fun onDestroy() {
        super.onDestroy()
        tone?.release()
        tone = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || currentPage != "level") return

        var x = event.values[0]
        var y = event.values[1]
        val z = event.values[2]

        // 根据屏幕旋转方向映射传感器坐标
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> {
                val oldX = x
                x = y
                y = -oldX
            }
            Surface.ROTATION_180 -> {
                x = -x
                y = -y
            }
            Surface.ROTATION_270 -> {
                val oldX = x
                x = -y
                y = oldX
            }
        }

        val rawX = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat()
        val rawY = Math.toDegrees(
            atan2(-x.toDouble(), sqrt((y * y + z * z).toDouble()))
        ).toFloat()

        smoothX = smoothX * 0.82f + rawX * 0.18f
        smoothY = smoothY * 0.82f + rawY * 0.18f

        angleX = smoothX - offsetX
        angleY = smoothY - offsetY
        updateLevel()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    inner class VialView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var horizontal = 0f
        private var vertical = 0f

        fun setAngles(x: Float, y: Float) {
            horizontal = x
            vertical = y
            invalidate()
        }

        private fun drawVial(
            canvas: Canvas,
            rect: RectF,
            bubbleOffset: Float,
            isHorizontal: Boolean
        ) {
            val radius = min(rect.width(), rect.height()) / 2f

            p.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    Color.rgb(65, 100, 95),
                    Color.rgb(15, 65, 46),
                    Color.rgb(135, 255, 85),
                    Color.rgb(30, 95, 55)
                ), null, Shader.TileMode.CLAMP
            )
            p.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, radius, radius, p)
            p.shader = null

            p.color = Color.argb(190, 225, 255, 240)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(3).toFloat()
            canvas.drawRoundRect(rect, radius, radius, p)

            p.color = Color.argb(90, 255, 255, 255)
            p.strokeWidth = dp(2).toFloat()
            val inner = RectF(
                rect.left + dp(7), rect.top + dp(7),
                rect.right - dp(7), rect.bottom - dp(7)
            )
            canvas.drawRoundRect(inner, radius, radius, p)

            val center = if (isHorizontal) rect.centerX() else rect.centerY()
            val markGap = if (isHorizontal) rect.width() * 0.20f else rect.height() * 0.20f

            p.color = Color.BLACK
            p.strokeWidth = dp(3).toFloat()
            if (isHorizontal) {
                for (m in listOf(center - markGap, center + markGap)) {
                    canvas.drawLine(m, rect.top + dp(5), m, rect.bottom - dp(5), p)
                }
            } else {
                for (m in listOf(center - markGap, center + markGap)) {
                    canvas.drawLine(rect.left + dp(5), m, rect.right - dp(5), m, p)
                }
            }

            val travel = if (isHorizontal) rect.width() * 0.28f else rect.height() * 0.28f
            val pos = center + (bubbleOffset / 15f).coerceIn(-1f, 1f) * travel

            val bubble = if (isHorizontal) {
                RectF(pos - dp(27), rect.centerY() - dp(17),
                    pos + dp(27), rect.centerY() + dp(17))
            } else {
                RectF(rect.centerX() - dp(17), pos - dp(27),
                    rect.centerX() + dp(17), pos + dp(27))
            }

            p.shader = RadialGradient(
                bubble.centerX() - dp(8), bubble.centerY() - dp(8),
                dp(45).toFloat(),
                intArrayOf(
                    Color.WHITE,
                    Color.argb(210, 225, 255, 205),
                    Color.argb(160, 135, 255, 85),
                    Color.argb(220, 20, 95, 35)
                ), null, Shader.TileMode.CLAMP
            )
            p.style = Paint.Style.FILL
            canvas.drawOval(bubble, p)
            p.shader = null

            p.color = Color.argb(210, 255, 255, 255)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(2).toFloat()
            canvas.drawOval(bubble, p)

            p.color = Color.argb(150, 255, 255, 255)
            p.style = Paint.Style.FILL
            canvas.drawOval(
                RectF(bubble.left + dp(8), bubble.top + dp(4),
                    bubble.right - dp(15), bubble.top + dp(10)), p
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val d = resources.displayMetrics.density

            if (isLandscapeMode) {
                val h = height.toFloat()
                // 横屏：横向水泡放左侧，纵向水泡放右侧
                val horizontalRect = RectF(
                    dp(8).toFloat(), h * 0.43f,
                    w * 0.64f, h * 0.43f + dp(48)
                )
                drawVial(canvas, horizontalRect, horizontal, true)

                val verticalRect = RectF(
                    w * 0.78f, dp(42).toFloat(),
                    w * 0.78f + dp(54), h - dp(12).toFloat()
                )
                drawVial(canvas, verticalRect, vertical, false)

                p.shader = null
                p.style = Paint.Style.FILL
                p.color = Color.WHITE
                p.textSize = dp(13).toFloat()
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("横向", w * 0.32f, dp(25).toFloat(), p)
                canvas.drawText("纵向", w * 0.78f + dp(27), h - dp(2).toFloat(), p)
            } else {
                val horizontalRect = RectF(
                    dp(12).toFloat(), dp(48).toFloat(),
                    w - dp(12), dp(112).toFloat()
                )
                drawVial(canvas, horizontalRect, horizontal, true)

                val verticalRect = RectF(
                    w / 2f - dp(34), dp(145).toFloat(),
                    w / 2f + dp(34), dp(320).toFloat()
                )
                drawVial(canvas, verticalRect, vertical, false)

                p.shader = null
                p.style = Paint.Style.FILL
                p.color = Color.WHITE
                p.textSize = dp(13).toFloat()
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("横向水泡", w / 2f, dp(32).toFloat(), p)
                canvas.drawText("纵向水泡", w / 2f, dp(338).toFloat(), p)
            }
        }
    }

    private fun showLandscapeLevel() {
        currentPage = "level"
        header.visibility = View.GONE
        tagline.visibility = View.GONE
        nav.visibility = View.GONE

        content.removeAllViews()
        content.orientation = LinearLayout.HORIZONTAL
        content.gravity = Gravity.CENTER
        content.setPadding(dp(4), dp(4), dp(4), dp(4))

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
        }

        left.addView(text("双轴气泡水平尺", 17f, white, true))
        statusText = text("正在读取传感器…", 17f, green, true)
        left.addView(statusText)

        vialView = VialView(this)
        left.addView(
            vialView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270)
            )
        )

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.rgb(17, 34, 54), 18)
        }

        angleText = text("横向 0.0°\n纵向 0.0°", 19f, white, true)
        angleText.setPadding(0, dp(8), 0, dp(8))
        right.addView(angleText)

        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        soundRow.addView(text("声音提示", 14f))
        val soundSwitch = Switch(this).apply {
            isChecked = soundEnabled
            setOnCheckedChangeListener { _, checked ->
                soundEnabled = checked
                if (!checked) tone?.stopTone()
            }
        }
        soundRow.addView(soundSwitch)
        right.addView(soundRow)

        thresholdLabel = text(
            "提示阈值：${"%.1f".format(threshold)}°",
            14f, Color.LTGRAY
        )
        right.addView(thresholdLabel)

        val seek = SeekBar(this).apply {
            max = 4
            progress = (threshold - 0.5f).toInt().coerceIn(0, 4)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        s: SeekBar?, p: Int, user: Boolean
                    ) {
                        threshold = 0.5f + p
                        thresholdLabel.text =
                            "提示阈值：${"%.1f".format(threshold)}°"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                }
            )
        }
        right.addView(seek)

        right.addView(button("校准当前水平零点") {
            offsetX += angleX
            offsetY += angleY
            angleX = 0f
            angleY = 0f
            smoothX = 0f
            smoothY = 0f
            updateLevel()
            Toast.makeText(this, "校准完成", Toast.LENGTH_SHORT).show()
        })

        right.addView(button("返回竖屏") {
            exitLandscape()
            showLevel()
        })

        content.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        content.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )

        updateLevel()
    }

    private fun preparePortraitPage() {
        exitLandscape()
        header.visibility = View.VISIBLE
        tagline.visibility = View.VISIBLE
        nav.visibility = View.VISIBLE
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(0, dp(12), 0, dp(20))
    }

    private fun showCamera() {
        preparePortraitPage()
        currentPage = "camera"
        tone?.stopTone()
        content.removeAllViews()
        content.addView(text("摄像头实景测量", 22f, white, true))
        content.addView(text("下一阶段接入 ARCore 空间测量", 16f, Color.LTGRAY))
        content.addView(text("扫描环境后，点击起点和终点计算长度。", 14f))
        content.addView(button("检查摄像头测量支持情况") {
            Toast.makeText(
                this,
                "摄像头测量模块将在下一阶段接入",
                Toast.LENGTH_LONG
            ).show()
        })
    }

    private fun showRuler() {
        preparePortraitPage()
        currentPage = "ruler"
        tone?.stopTone()
        content.removeAllViews()

        content.addView(text("手机屏幕电子尺", 22f, white, true))
        content.addView(
            text("屏幕刻度精度受设备参数影响，请先用标准尺校准", 13f, Color.LTGRAY)
        )

        val scroll = HorizontalScrollView(this)
        val ruler = RulerView(this)
        scroll.addView(ruler)
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)
            )
        )
        content.addView(text("厘米 / 毫米刻度 · 30厘米长度", 16f))
    }

    inner class RulerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dpi = resources.displayMetrics.xdpi.coerceAtLeast(120f)
        private val pixelsPerCm = dpi / 2.54f
        private val rulerWidth = (pixelsPerCm * 30f).toInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                max(rulerWidth, 1),
                dp(180)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = white
            paint.textSize = dp(13).toFloat()
            canvas.drawText("厘米", dp(8).toFloat(), dp(24).toFloat(), paint)

            for (i in 0..300) {
                val x = i / 10f * pixelsPerCm
                val len = when {
                    i % 10 == 0 -> dp(44).toFloat()
                    i % 5 == 0 -> dp(30).toFloat()
                    else -> dp(15).toFloat()
                }
                paint.strokeWidth = dp(1).toFloat()
                canvas.drawLine(x, dp(50).toFloat(), x, dp(50).toFloat() + len, paint)

                if (i % 10 == 0) {
                    canvas.drawText(
                        "${i / 10}", x + dp(3), dp(155).toFloat(), paint
                    )
                }
            }
        }
    }
}
