package com.xiangxiang.measure

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.graphics.SurfaceTexture
import android.opengl.Matrix
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ArMeasureActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var info: TextView
    private lateinit var distanceText: TextView
    private lateinit var unitSpinner: Spinner

    private var session: Session? = null
    private var installRequested = false
    private var distanceMeters = 0f
    private var unit = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }

        renderer = ArRenderer { count, distance, message ->
            runOnUiThread {
                info.text = message
                if (count == 2 && distance != null) {
                    distanceMeters = distance
                    updateDistance()
                } else {
                    distanceText.text =
                        if (count == 1) "已设置起点 A，请选择终点 B"
                        else "等待测量"
                }
            }
        }

        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y
                glView.queueEvent {
                    renderer.addPoint(x, y)
                }
                true
            } else true
        }

        root.addView(
            glView,
            FrameLayout.LayoutParams(-1, -1)
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0xCC10243A.toInt())
        }

        info = TextView(this).apply {
            text = "正在准备 AR 相机…"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        distanceText = TextView(this).apply {
            text = "等待测量"
            textSize = 25f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
        }

        unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ArMeasureActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("米", "厘米", "毫米")
            )
            onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    unit = position
                    updateDistance()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttons.addView(Button(this).apply {
            text = "清除重测"
            setOnClickListener {
                glView.queueEvent { renderer.clearPoints() }
            }
        })

        buttons.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })

        panel.addView(info)
        panel.addView(distanceText)
        panel.addView(unitSpinner)
        panel.addView(buttons)

        val params = FrameLayout.LayoutParams(
            -1, -2, Gravity.TOP
        )
        root.addView(panel, params)

        setContentView(root)
    }

    private fun updateDistance() {
        val value = when (unit) {
            1 -> distanceMeters * 100f
            2 -> distanceMeters * 1000f
            else -> distanceMeters
        }
        val suffix = listOf("m", "cm", "mm")[unit]
        distanceText.text =
            String.format(Locale.US, "%.3f %s", value, suffix)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        ensureArSession()
    }

    private fun ensureArSession() {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA), 100
            )
            return
        }

        try {
            val installStatus =
                ArCoreApk.getInstance().requestInstall(
                    this, !installRequested
                )

            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }

            if (session == null) {
                val newSession = Session(this)
                val config = Config(newSession).apply {
                    planeFindingMode =
                        Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                }
                newSession.configure(config)
                session = newSession
                glView.queueEvent {
                    renderer.attachSession(newSession)
                }
            }

            session?.resume()

        } catch (e: Exception) {
            info.text = "AR启动失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureArSession()
            } else {
                info.text = "需要摄像头权限才能使用 AR 测距"
            }
        }
    }

    override fun onPause() {
        session?.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private class ArRenderer(
        private val callback: (Int, Float?, String) -> Unit
    ) : GLSurfaceView.Renderer {

        @Volatile private var session: Session? = null

        private var textureId = 0
        private var program = 0
        private var positionHandle = 0
        private var uvHandle = 0
        private var textureHandle = 0

        private var width = 1
        private var height = 1
        private var frame: Frame? = null
        private val points = mutableListOf<Pose>()

        private val vertices = floatArrayOf(
            -1f, -1f, 1f, -1f,
            -1f, 1f, 1f, 1f
        )

        private val uv = floatArrayOf(
            0f, 1f, 1f, 1f,
            0f, 0f, 1f, 0f
        )

        private val uvBuffer = buffer(uv)
        private val transformedUv = buffer(FloatArray(8))
        private val vertexBuffer = buffer(vertices)

        fun attachSession(value: Session) {
            session = value
            if (textureId != 0) {
                value.setCameraTextureNames(intArrayOf(textureId))
            }
        }

        override fun onSurfaceCreated(
            gl: GL10?, config: EGLConfig?
        ) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]

            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )

            val vertexShader = """
                attribute vec2 aPosition;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vUv = aUv;
                }
            """.trimIndent()

            val fragmentShader = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES cameraTexture;
                varying vec2 vUv;
                void main() {
                    gl_FragColor = texture2D(cameraTexture, vUv);
                }
            """.trimIndent()

            program = createProgram(vertexShader, fragmentShader)
            positionHandle =
                GLES20.glGetAttribLocation(program, "aPosition")
            uvHandle = GLES20.glGetAttribLocation(program, "aUv")
            textureHandle =
                GLES20.glGetUniformLocation(program, "cameraTexture")

            session?.setCameraTextureNames(intArrayOf(textureId))
        }

        override fun onSurfaceChanged(
            gl: GL10?, w: Int, h: Int
        ) {
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val activeSession = session ?: return

            try {
                activeSession.setDisplayGeometry(
                    android.view.Surface.ROTATION_90,
                    width, height
                )

                val current = activeSession.update()
                frame = current

                if (current.camera.trackingState != TrackingState.TRACKING) {
                    callback(
                        points.size, null,
                        "正在识别环境，请缓慢移动手机扫描表面"
                    )
                }

                drawCamera(current)

            } catch (e: Exception) {
                callback(
                    points.size, null,
                    "AR运行中：${e.message ?: "正在恢复跟踪"}"
                )
            }
        }

        private fun drawCamera(current: Frame) {
            val input = buffer(uv)
            val output = buffer(FloatArray(8))

            current.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                input,
                Coordinates2d.TEXTURE_NORMALIZED,
                output
            )

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(program)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle, 2, GLES20.GL_FLOAT,
                false, 0, vertexBuffer
            )

            output.position(0)
            GLES20.glEnableVertexAttribArray(uvHandle)
            GLES20.glVertexAttribPointer(
                uvHandle, 2, GLES20.GL_FLOAT,
                false, 0, output
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId
            )
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP, 0, 4
            )

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(uvHandle)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        fun addPoint(x: Float, y: Float) {
            val current = frame ?: return
            if (current.camera.trackingState != TrackingState.TRACKING) {
                callback(points.size, null, "请先扫描环境，等待 AR 跟踪")
                return
            }

            try {
                val hit = current.hitTest(x, y).firstOrNull { result ->
                    val trackable = result.trackable
                    trackable is Plane &&
                        trackable.trackingState == TrackingState.TRACKING &&
                        trackable.isPoseInPolygon(result.hitPose)
                }

                if (hit == null) {
                    callback(
                        points.size, null,
                        "未命中已识别平面，请对准纹理清晰的表面再点"
                    )
                    return
                }

                points.add(hit.hitPose)

                if (points.size == 1) {
                    callback(1, null, "起点 A 已记录，请点击终点 B")
                } else {
                    val a = points[0]
                    val b = points[1]
                    val dx = b.tx() - a.tx()
                    val dy = b.ty() - a.ty()
                    val dz = b.tz() - a.tz()
                    val distance = sqrt(
                        dx * dx + dy * dy + dz * dz
                    )
                    callback(2, distance, "测量完成：A 到 B 的空间直线距离")
                }

                if (points.size >= 2) {
                    // 完成一组测量后保留结果，下一次点击重新开始
                    points.clear()
                }

            } catch (e: Exception) {
                callback(points.size, null, "测量失败，请重新扫描")
            }
        }

        fun clearPoints() {
            points.clear()
            callback(0, null, "已清除，请重新选择起点 A")
        }

        private fun buffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(
                    shader, GLES20.GL_COMPILE_STATUS, status, 0
                )
                if (status[0] == 0) {
                    val error = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw RuntimeException(error)
                }
                return shader
            }

            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vs)
            GLES20.glAttachShader(result, fs)
            GLES20.glLinkProgram(result)

            val status = IntArray(1)
            GLES20.glGetProgramiv(
                result, GLES20.GL_LINK_STATUS, status, 0
            )
            if (status[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(result)
                GLES20.glDeleteProgram(result)
                throw RuntimeException(error)
            }

            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return result
        }
    }
}
