package com.melhive.airx1.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.engine.HandFrame
import com.melhive.airx1.engine.Handedness
import com.melhive.airx1.engine.Landmark
import com.melhive.airx1.log.LogBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Front camera (CameraX ImageAnalysis) → upright + mirrored bitmap → MediaPipe HandLandmarker
 * (LIVE_STREAM). Results are delivered to [onFrame] on MediaPipe's thread.
 *
 * Frames live only in memory and are dropped right after processing. Nothing is saved or sent.
 */
class HandTracker(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onFrame: (HandFrame?, Long) -> Unit,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    @Volatile private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    @Volatile private var paused = false
    @Volatile private var lastHandSeenMs = 0L
    private var lastProcessedMs = 0L
    private var lastTimestamp = 0L
    private var previewCounter = 0
    private var fpsCount = 0
    private var fpsWindowStart = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) analysis?.targetRotation = currentRotation()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    fun start() {
        analysisExecutor.execute { landmarker = createLandmarker() }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                if (!paused) bind()
            } catch (e: Exception) {
                LogBuffer.e("Camera provider failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    /** Main thread. Releases the camera (screen off). */
    fun pause() {
        if (paused) return
        paused = true
        provider?.unbindAll()
        analysis = null
        LogBuffer.i("Camera paused")
    }

    /** Main thread. */
    fun resume() {
        if (!paused) return
        paused = false
        bind()
        LogBuffer.i("Camera resumed")
    }

    /** Main thread. */
    fun stop() {
        paused = true
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        provider?.unbindAll()
        analysis = null
        analysisExecutor.execute {
            runCatching { landmarker?.close() }
            landmarker = null
        }
        analysisExecutor.shutdown()
        GestureBus.preview.value = null
        GestureBus.fps.value = 0f
    }

    private fun currentRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    private fun bind() {
        val p = provider ?: return
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(currentRotation())
            .build()
        a.setAnalyzer(analysisExecutor, ::analyze)
        try {
            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
            analysis = a
            LogBuffer.i("Front camera bound (${a.resolutionInfo?.resolution ?: "resolution pending"})")
        } catch (e: Exception) {
            LogBuffer.e("Could not open the front camera", e)
        }
    }

    private fun createLandmarker(): HandLandmarker? {
        // CPU is the default: the GPU delegate can crash natively on some devices,
        // and CPU is fast enough for a single hand at 640x480.
        return try {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: HandLandmarkerResult, image: MPImage -> onResult(result, image) }
                .setErrorListener { e: RuntimeException -> LogBuffer.e("HandLandmarker error", e) }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                GestureBus.delegate.value = "CPU"
                LogBuffer.i("HandLandmarker ready (CPU)")
            }
        } catch (e: Throwable) {
            LogBuffer.e("HandLandmarker failed to load (is hand_landmarker.task in assets?)", e)
            null
        }
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val lm = landmarker
        // ~30 fps while a hand is around; ~12 fps after IDLE_AFTER_MS without one.
        val minInterval = if (now - lastHandSeenMs > IDLE_AFTER_MS) IDLE_FRAME_INTERVAL_MS else 0L
        if (paused || lm == null || now - lastProcessedMs < minInterval) {
            image.close()
            return
        }
        lastProcessedMs = now

        val upright: Bitmap
        try {
            val src = image.toBitmap()
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f) // mirror: front camera behaves like a mirror, as MediaPipe expects
            }
            upright = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        } catch (e: Exception) {
            LogBuffer.e("Frame conversion failed", e)
            return
        } finally {
            image.close()
        }

        val ts = if (now <= lastTimestamp) lastTimestamp + 1 else now
        lastTimestamp = ts
        try {
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (e: Exception) {
            LogBuffer.e("detectAsync failed", e)
        }

        if (GestureBus.previewViewers.get() > 0) {
            if (previewCounter++ % 2 == 0) GestureBus.preview.value = upright
        } else if (GestureBus.preview.value != null) {
            GestureBus.preview.value = null
        }
    }

    private fun onResult(result: HandLandmarkerResult, image: MPImage) {
        val now = SystemClock.uptimeMillis()
        countFps(now)
        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < 21) {
            onFrame(null, now)
            return
        }
        lastHandSeenMs = now
        val landmarks = hand.map { Landmark(it.x(), it.y(), it.z()) }
        // Input is mirrored (selfie), which is what MediaPipe's handedness labels assume.
        val label = result.handedness().firstOrNull()?.firstOrNull()?.categoryName()
        val handedness = when (label?.lowercase()) {
            "left" -> Handedness.LEFT
            "right" -> Handedness.RIGHT
            else -> Handedness.UNKNOWN
        }
        val aspect = if (image.height > 0) image.width.toFloat() / image.height else 1f
        onFrame(HandFrame(landmarks, handedness, aspect), now)
    }

    private fun countFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsCount++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            GestureBus.fps.value = fpsCount * 1000f / elapsed
            fpsCount = 0
            fpsWindowStart = now
        }
    }

    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        private const val IDLE_AFTER_MS = 3000L
        private const val IDLE_FRAME_INTERVAL_MS = 80L
    }
}
