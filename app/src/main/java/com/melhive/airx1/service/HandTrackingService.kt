package com.melhive.airx1.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.melhive.airx1.R
import com.melhive.airx1.bus.GestureBus
import com.melhive.airx1.data.SettingsRepository
import com.melhive.airx1.engine.EngineSnapshot
import com.melhive.airx1.engine.GestureEngine
import com.melhive.airx1.engine.GestureEvent
import com.melhive.airx1.engine.HandFrame
import com.melhive.airx1.log.LogBuffer
import com.melhive.airx1.ui.MainActivity
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service (type camera) that owns the camera pipeline and the gesture engine.
 * Must be started while the activity is visible (Android's while-in-use camera rule).
 */
class HandTrackingService : LifecycleService() {

    private val engine = GestureEngine()
    private val engineExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var tracker: HandTracker? = null
    private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    disarm("screen off")
                    tracker?.pause()
                }
                Intent.ACTION_SCREEN_ON -> tracker?.resume()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            LogBuffer.i("Stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            LogBuffer.e("Cannot start: camera permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (e: Exception) {
            LogBuffer.e("Could not start foreground service (open AirX1 and press Start)", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startPipeline()
        return START_NOT_STICKY
    }

    private fun startPipeline() {
        LogBuffer.i("Engine starting")
        GestureBus.engineRunning.value = true

        lifecycleScope.launch {
            SettingsRepository(this@HandTrackingService).flow.collect { settings ->
                val cfg = settings.toEngineConfig()
                post { engine.config = cfg }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        tracker = HandTracker(this, this) { frame, now ->
            post { onFrame(frame, now) }
        }.also { it.start() }
    }

    /** Engine thread. */
    private fun onFrame(frame: HandFrame?, nowMs: Long) {
        val out = engine.process(frame, nowMs)
        GestureBus.snapshot.value = out.snapshot
        out.events.forEach(::publish)
    }

    private fun publish(event: GestureEvent) {
        if (event is GestureEvent.ArmChanged) {
            LogBuffer.i(if (event.armed) "ARMED (${event.reason})" else "DISARMED (${event.reason})")
        } else {
            LogBuffer.d("Gesture: $event")
        }
        GestureBus.emit(event)
    }

    /** Runs [block] on the engine thread; silently dropped once the service is shutting down. */
    private fun post(block: () -> Unit) {
        try {
            engineExecutor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun disarm(reason: String) {
        post {
            engine.disarm(reason, SystemClock.uptimeMillis())?.let {
                GestureBus.snapshot.value = GestureBus.snapshot.value.copy(armed = false)
                publish(it)
            }
        }
    }

    override fun onDestroy() {
        if (running) {
            runCatching { unregisterReceiver(screenReceiver) }
            tracker?.stop()
            tracker = null
            disarm("engine stopped")
        }
        engineExecutor.shutdown()
        running = false
        GestureBus.engineRunning.value = false
        GestureBus.snapshot.value = EngineSnapshot()
        LogBuffer.i("Engine stopped")
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HandTrackingService::class.java).setAction(ACTION_STOP), flags
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airx1)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.melhive.airx1.action.START"
        const val ACTION_STOP = "com.melhive.airx1.action.STOP"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1

        /** Call only while the activity is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, HandTrackingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandTrackingService::class.java))
        }
    }
}
