package com.xiangxiang.measure

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.View
import android.view.Gravity
import android.widget.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var angleX = 0f
    private var angleY = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private lateinit var angleText: TextView
    private lateinit var statusText: TextView
    private lateinit var bubbleView: BubbleView
    private lateinit var content: LinearLayout

    private var currentPage = "level"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        buildUI()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 12)
            setBackgroundColor(Color.rgb(15, 23, 42))
        }

        val title = TextView(this).apply {
            text = "湘湘智能测量尺"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "专业测量工具箱 · 离线版"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }
        root.addView(subtitle)

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        menu.addView(menuButton("水平仪") { showLevel() })
        menu.addView(menuButton("电子尺") { showRuler() })

        root.addView(menu)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        showLevel()
    }

    private fun menuButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setOnClickListener { action() }
        }
    }

    private fun showLevel() {
        currentPage = "level"
        content.removeAllViews()

        val heading = TextView(this).apply {
            text = "实时水平仪"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(heading)

        statusText = TextView(this).apply {
            text = "正在读取传感器..."
            textSize = 17f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        content.addView(statusText)

        bubbleView = BubbleView(this)
        content.addView(
            bubbleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        angleText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        content.addView(angleText)

        val calibrate = Button(this).apply {
            text = "校准水平零点"
            setOnClickListener {
                offsetX = angleX + offsetX
                offsetY = angleY + offsetY
                angleX = 0f
                angleY = 0f
                updateLevel()
                Toast.makeText(
                    this@MainActivity,
                    "校准完成",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        content.addView(calibrate)

        updateLevel()
    }

    private fun showRuler() {
        currentPage = "ruler"
        content.removeAllViews()

        val heading = TextView(this).apply {
            text = "手机电子直尺"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(heading)

        val info = TextView(this).apply {
            text = "根据手机屏幕物理尺寸绘制刻度，精度受设备屏幕参数影响"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 15)
        }
        content.addView(info)

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = true

        val ruler = RulerView(this)
        scroll.addView(ruler)

        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                190
            )
        )

        val unitText = TextView(this).apply {
            text = "刻度单位：厘米 / 毫米"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        content.addView(unitText)

        val note = TextView(this).apply {
            text = "提示：屏幕电子尺仅供参考。可使用标准尺校验屏幕刻度。"
            textSize = 13f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(8, 15, 8, 15)
        }
        content.addView(note)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || currentPage != "level") return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        angleX = Math.toDegrees(
            atan2(y.toDouble(), z.toDouble())
        ).toFloat() - offsetX

        angleY = Math.toDegrees(
            atan2(
                -x.toDouble(),
                sqrt((y * y + z * z).toDouble())
            )
        ).toFloat() - offsetY

        updateLevel()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateLevel() {
        if (currentPage != "level") return

        if (!::angleText.isInitialized) return

        angleText.text = String.format(
            java.util.Locale.US,
            "X轴：%.1f°       Y轴：%.1f°",
            angleX,
            angleY
        )

        val total = sqrt(
            angleX * angleX + angleY * angleY
        )

        statusText.text = if (total < 1.0f) {
            "● 水平状态良好"
        } else if (total < 3.0f) {
            "● 接近水平"
        } else {
            "● 存在倾斜"
        }

        statusText.setTextColor(
            if (total < 1.0f) Color.GREEN else Color.YELLOW
        )

        if (::bubbleView.isInitialized) {
            bubbleView.setAngles(angleX, angleY)
        }
    }

    inner class BubbleView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ax = 0f
        private var ay = 0f

        fun setAngles(x: Float, y: Float) {
            ax = x
            ay = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.34f

            paint.color = Color.rgb(35, 48, 70)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius + 12f, paint)

            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawCircle(cx, cy, radius, paint)

            paint.color = Color.GRAY
            paint.strokeWidth = 2f
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

            paint.color = Color.GREEN
            paint.strokeWidth = 3f
            canvas.drawCircle(cx, cy, 10f, paint)

            val maxMove = radius * 0.75f
            val bx = cx + (ax / 15f).coerceIn(-1f, 1f) * maxMove
            val by = cy + (ay / 15f).coerceIn(-1f, 1f) * maxMove

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(22, 180, 120)
            canvas.drawCircle(bx, by, 22f, paint)

            paint.color = Color.WHITE
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("气泡水平仪", cx, cy + radius + 55f, paint)
        }
    }

    inner class RulerView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val screenDpi = resources.displayMetrics.xdpi
        private val pixelsPerCm = screenDpi / 2.54f

        private val rulerWidth = (pixelsPerCm * 30f).toInt()

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            setMeasuredDimension(
                max(rulerWidth, 1),
                (180 * resources.displayMetrics.density).toInt()
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            paint.color = Color.WHITE
            paint.textSize = 15f * resources.displayMetrics.density

            canvas.drawText(
                "厘米",
                10f,
                25f,
                paint
            )

            val startY = 55f
            val endY = height - 20f

            for (i in 0..300) {
                val x = i / 10f * pixelsPerCm

                val lineLength = when {
                    i % 10 == 0 -> 45f
                    i % 5 == 0 -> 30f
                    else -> 15f
                }

                paint.color = Color.WHITE
                paint.strokeWidth = 2f

                canvas.drawLine(
                    x,
                    startY,
                    x,
                    startY + lineLength,
                    paint
                )

                if (i % 10 == 0) {
                    paint.textSize = 13f * resources.displayMetrics.density
                    canvas.drawText(
                        "${i / 10}",
                        x + 3f,
                        endY,
                        paint
                    )
                }
            }
        }
    }
}
