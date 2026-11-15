package com.example.duofoldwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceHolder
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "DuoWallpaperService"

internal fun interface HingeListener {
    fun onHingeAngle(degrees: Float, fromRoot: Boolean)
}

internal class RootShell private constructor(
    private val process: java.lang.Process,
    private val stdin: BufferedWriter,
    private val stdout: BufferedReader
) {
    @Synchronized
    fun run(command: String): List<String> {
        stdin.write(command)
        stdin.write("; echo $SENTINEL")
        stdin.newLine()
        stdin.flush()
        val lines = ArrayList<String>()
        while (true) {
            val line = stdout.readLine() ?: break
            if (line == SENTINEL) break
            lines.add(line)
        }
        return lines
    }

    fun close() {
        try { stdin.close() } catch (e: Exception) {}
        try { process.destroy() } catch (e: Exception) {}
    }

    companion object {
        private const val SENTINEL = "__DUO_DONE__"
        fun open(): RootShell? {
            return try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                val shell = RootShell(
                    p,
                    BufferedWriter(OutputStreamWriter(p.outputStream)),
                    BufferedReader(InputStreamReader(p.inputStream))
                )
                if (shell.run("id").any { it.contains("uid=0") }) shell else { shell.close(); null }
            } catch (t: Throwable) {
                null
            }
        }
    }
}

internal class RootHingeSource(
    private val mainHandler: Handler,
    private val listener: HingeListener
) {
    @Volatile private var alive = true
    @Volatile private var active = false
    @Volatile private var unavailable = false
    private var thread: Thread? = null

    @Synchronized
    fun resume() {
        if (!alive || unavailable) return
        active = true
        val t = thread
        if (t == null) {
            thread = Thread({ pollLoop() }, "DuoHingeRootPoll").apply {
                isDaemon = true
                start()
            }
        } else {
            LockSupport.unpark(t)
        }
    }

    fun pause() { active = false }

    @Synchronized
    fun release() {
        alive = false
        active = false
        thread?.let { LockSupport.unpark(it); it.interrupt() }
        thread = null
    }

    private fun pollLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (t: Throwable) {}
        val sh = RootShell.open()
        if (sh == null) { unavailable = true; return }
        try {
            sh.run("chrt -f -p 2 ${Process.myTid()}")
            var lastDegrees = Float.NaN
            while (alive) {
                if (!active) { LockSupport.park(this); continue }
                // Example hinge node poll or fallback query
                val raw = sh.run("cat /sys/bus/iio/devices/iio:device*/in_angl*_raw 2>/dev/null").firstOrNull()?.trim()?.toFloatOrNull()
                if (raw != null) {
                    val degrees = raw * (180f / PI.toFloat())
                    if (degrees != lastDegrees) {
                        lastDegrees = degrees
                        mainHandler.post { listener.onHingeAngle(degrees, true) }
                    }
                }
                Thread.sleep(4L)
            }
        } catch (e: InterruptedException) {
        } finally {
            sh.close()
        }
    }
}

class DuoWallpaperService : WallpaperService() {

    companion object {
        private const val SETTLE_EPSILON = 0.0001f
        private const val BLUR_DOWNSCALE = 2f

        const val PREFS_NAME = "duo_wallpaper_prefs"
        const val PREF_CUSTOM_IMAGE = "custom_image_path"
        const val PREF_DEMO_MODE = "demo_mode"
        const val CUSTOM_IMAGE_FILE = "custom_wallpaper.png"
    }

    override fun onCreateEngine(): Engine = DuoEngine()

    private inner class DuoEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val mainHandler = Handler(Looper.getMainLooper())
        private val choreographer = Choreographer.getInstance()
        private val runtimeShader = RuntimeShader(ShaderCode.DUO_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }

        private lateinit var sensorManager: SensorManager
        private lateinit var prefs: SharedPreferences
        private lateinit var deviceConfig: DeviceConfig
        private var hingeSensor: Sensor? = null
        private var rootHingeSource: RootHingeSource? = null

        private val hingeListener = HingeListener { degrees, _ -> onHingeDegrees(degrees) }

        private var displayedFold = 0f
        private var targetFold = 0f

        private var canvasWidth = 0f
        private var canvasHeight = 0f
        private var imageWidth = 0f
        private var imageHeight = 0f

        private var isOuterDisplay = false
        private var displayRoleResolved = false
        private var frameCallbackPosted = false

        private var demoMode = false
        private var demoPhase = 0f
        private var lastFrameTimeNanos = 0L

        private val frameCallback = Choreographer.FrameCallback { now ->
            frameCallbackPosted = false
            if (isVisible) {
                drawFrame(now)
                val stillMoving = demoMode || abs(targetFold - displayedFold) > SETTLE_EPSILON
                if (stillMoving) postFrameCallbackIfNeeded()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            deviceConfig = DeviceConfig.detect(this@DuoWallpaperService)
            runtimeShader.setFloatUniform("uHingePos", deviceConfig.hingeRatio)
            runtimeShader.setFloatUniform("uEyeRatio", deviceConfig.perspectiveRatio)

            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
            demoMode = prefs.getBoolean(PREF_DEMO_MODE, false)

            loadAndBindWallpaper()

            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

            rootHingeSource = RootHingeSource(mainHandler, hingeListener)
        }

        private fun loadAndBindWallpaper() {
            val bitmap = loadWallpaperBitmap()
            imageWidth = bitmap.width.toFloat()
            imageHeight = bitmap.height.toFloat()

            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            runtimeShader.setInputShader("uImage", bitmapShader)

            val blurW = (bitmap.width / BLUR_DOWNSCALE).toInt().coerceAtLeast(1)
            val blurH = (bitmap.height / BLUR_DOWNSCALE).toInt().coerceAtLeast(1)
            val blurBitmap = Bitmap.createScaledBitmap(bitmap, blurW, blurH, true)
            val blurShader = BitmapShader(blurBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            runtimeShader.setInputShader("uImageBlur", blurShader)
            runtimeShader.setFloatUniform("uBlurScale", BLUR_DOWNSCALE)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            resolveDisplayRole()
        }

        private fun resolveDisplayRole() {
            val displayId = if (Build.VERSION.SDK_INT >= 34) displayContext?.display?.displayId else null
            isOuterDisplay = displayId != null && displayId != Display.DEFAULT_DISPLAY
            displayRoleResolved = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            canvasWidth = width.toFloat()
            canvasHeight = height.toFloat()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                hingeSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                }
                rootHingeSource?.resume()
                lastFrameTimeNanos = System.nanoTime()
                postFrameCallbackIfNeeded()
            } else {
                sensorManager.unregisterListener(this)
                rootHingeSource?.pause()
                stopFrameLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            sensorManager.unregisterListener(this)
            rootHingeSource?.pause()
            stopFrameLoop()
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            sensorManager.unregisterListener(this)
            rootHingeSource?.release()
            stopFrameLoop()
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
            if (demoMode) return
            onHingeDegrees(event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun onHingeDegrees(degrees: Float) {
            targetFold = ((180.0 - degrees) * PI / 180.0).toFloat().coerceIn(0f, PI.toFloat())
            postFrameCallbackIfNeeded()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            when (key) {
                PREF_CUSTOM_IMAGE -> loadAndBindWallpaper()
                PREF_DEMO_MODE -> {
                    demoMode = prefs.getBoolean(PREF_DEMO_MODE, false)
                    if (demoMode) {
                        val currentDegrees = (180f - displayedFold * 180f / PI.toFloat()).coerceIn(0f, 180f)
                        demoPhase = FoldMath.demoPhaseForAngle(currentDegrees)
                        lastFrameTimeNanos = System.nanoTime()
                    }
                    postFrameCallbackIfNeeded()
                }
            }
        }

        private fun postFrameCallbackIfNeeded() {
            if (!frameCallbackPosted && isVisible) {
                frameCallbackPosted = true
                choreographer.postFrameCallback(frameCallback)
            }
        }

        private fun stopFrameLoop() {
            choreographer.removeFrameCallback(frameCallback)
            frameCallbackPosted = false
        }

        private fun drawFrame(nowNanos: Long) {
            if (!displayRoleResolved) resolveDisplayRole()
            if (canvasWidth <= 0f || canvasHeight <= 0f) return

            val deltaSec = min((nowNanos - lastFrameTimeNanos) / 1_000_000_000f, 0.05f)
            lastFrameTimeNanos = nowNanos

            if (demoMode) {
                demoPhase = (demoPhase + deltaSec) % FoldMath.DEMO_CYCLE_DURATION
                val degrees = FoldMath.demoAngleDegrees(demoPhase)
                displayedFold = ((180f - degrees) * PI.toFloat() / 180f).coerceIn(0f, PI.toFloat())
            } else {
                // High-responsiveness direct lerp for real-time feel
                displayedFold += (targetFold - displayedFold) * 0.35f
            }

            val holder = surfaceHolder
            val canvas: Canvas = (holder.lockHardwareCanvas() ?: holder.lockCanvas()) ?: return
            try {
                val effectiveIsOuter = isOuterDisplay || (canvasHeight > canvasWidth * 1.5f)

                val progress = if (effectiveIsOuter) {
                    ((PI.toFloat() - displayedFold) / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
                } else {
                    (displayedFold / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
                }
                val motion = FoldMath.smoothstep(progress)

                runtimeShader.setFloatUniform("uPixel", 1f / imageWidth, 1f / imageHeight)
                runtimeShader.setFloatUniform("uMotion", motion)
                runtimeShader.setFloatUniform("uRadiusMax", deviceConfig.maxBlurRadius * 0.55f)

                if (effectiveIsOuter) {
                    val win = FoldMath.computeCoverUvWindow(canvasWidth, canvasHeight, deviceConfig)
                    runtimeShader.setFloatUniform("uUvOffset", win.offsetX, win.offsetY)
                    runtimeShader.setFloatUniform("uUvScale", win.scaleX / canvasWidth, win.scaleY / canvasHeight)
                    runtimeShader.setFloatUniform("uGrad", win.offsetX, win.offsetX + win.scaleX)
                    runtimeShader.setFloatUniform("uFold", 0f)
                } else {
                    runtimeShader.setFloatUniform("uUvOffset", 0f, 0f)
                    runtimeShader.setFloatUniform("uUvScale", 1f / canvasWidth, 1f / canvasHeight)
                    runtimeShader.setFloatUniform("uGrad", deviceConfig.hingeRatio, 0f)
                    runtimeShader.setFloatUniform("uFold", displayedFold)
                }

                canvas.drawRect(0f, 0f, canvasWidth, canvasHeight, paint)
            } catch (t: Throwable) {
                Log.e(TAG, "drawFrame failed", t)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun loadWallpaperBitmap(): Bitmap {
            val customFile = File(filesDir, CUSTOM_IMAGE_FILE)
            if (customFile.exists()) {
                val opts = BitmapFactory.Options().apply { inScaled = false }
                BitmapFactory.decodeFile(customFile.absolutePath, opts)?.let { return it }
            }
            val resId = resources.getIdentifier("wallpaper", "drawable", packageName)
            if (resId != 0) {
                val opts = BitmapFactory.Options().apply { inScaled = false }
                BitmapFactory.decodeResource(resources, resId, opts)?.let { return it }
            }
            val w = max(deviceConfig.innerWidthPx, 1)
            val h = max(deviceConfig.innerHeightPx, 1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF1E1E2E.toInt(), 0xFF4B2E83.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = gradient })
            return bmp
        }
    }
}
