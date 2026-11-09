package com.melhive.airx1.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of the last [CAPACITY] log lines, shown on the Log screen and
 * mirrored to logcat with tag "AirX1" (read it from Termux with `adb logcat -s AirX1`).
 * Nothing is written to disk.
 */
object LogBuffer {
    const val TAG = "AirX1"
    private const val CAPACITY = 500

    private val lines = ArrayDeque<String>(CAPACITY)
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(msg: String) = add("D", msg) { Log.d(TAG, msg) }
    fun i(msg: String) = add("I", msg) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) = add("W", msg + (t?.let { ": ${it.message}" } ?: "")) { Log.w(TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) = add("E", msg + (t?.let { ": ${it.message}" } ?: "")) { Log.e(TAG, msg, t) }

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
    }

    @Synchronized
    private fun add(level: String, msg: String, logcat: () -> Unit) {
        logcat()
        val line = synchronized(timeFormat) { timeFormat.format(Date()) } + " $level " + msg
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
        _flow.value = lines.toList()
    }
}
