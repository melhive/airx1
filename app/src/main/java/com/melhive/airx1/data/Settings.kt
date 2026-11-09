package com.melhive.airx1.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.melhive.airx1.engine.CursorAnchor
import com.melhive.airx1.engine.EngineConfig
import com.melhive.airx1.engine.HandPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.pow

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val regionLeft: Float = 0.2f,
    val regionTop: Float = 0.2f,
    val regionRight: Float = 0.8f,
    val regionBottom: Float = 0.8f,
    val sensitivity: Float = 1f,
    /** 0 = light smoothing (fast, jittery), 1 = heavy smoothing (steady, laggy). */
    val smoothing: Float = 0.5f,
    val pinchOn: Float = 0.30f,
    val pinchOff: Float = 0.45f,
    val hand: HandPreference = HandPreference.ANY,
    val anchor: CursorAnchor = CursorAnchor.KNUCKLE,
    val calibrated: Boolean = false,
) {
    fun toEngineConfig() = EngineConfig(
        regionLeft = regionLeft,
        regionTop = regionTop,
        regionRight = regionRight,
        regionBottom = regionBottom,
        sensitivity = sensitivity,
        // Log scale: 0 → 3 Hz, 0.5 → ~0.95 Hz, 1 → 0.3 Hz.
        minCutoff = (3.0 * 0.1.pow(smoothing.toDouble())).toFloat(),
        beta = 4f,
        pinchOn = pinchOn,
        pinchOff = pinchOff,
        handPreference = hand,
        anchor = anchor,
    )
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object K {
        val regionLeft = floatPreferencesKey("region_left")
        val regionTop = floatPreferencesKey("region_top")
        val regionRight = floatPreferencesKey("region_right")
        val regionBottom = floatPreferencesKey("region_bottom")
        val sensitivity = floatPreferencesKey("sensitivity")
        val smoothing = floatPreferencesKey("smoothing")
        val pinchOn = floatPreferencesKey("pinch_on")
        val pinchOff = floatPreferencesKey("pinch_off")
        val hand = stringPreferencesKey("hand")
        val anchor = stringPreferencesKey("anchor")
        val calibrated = booleanPreferencesKey("calibrated")
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            regionLeft = p[K.regionLeft] ?: d.regionLeft,
            regionTop = p[K.regionTop] ?: d.regionTop,
            regionRight = p[K.regionRight] ?: d.regionRight,
            regionBottom = p[K.regionBottom] ?: d.regionBottom,
            sensitivity = p[K.sensitivity] ?: d.sensitivity,
            smoothing = p[K.smoothing] ?: d.smoothing,
            pinchOn = p[K.pinchOn] ?: d.pinchOn,
            pinchOff = p[K.pinchOff] ?: d.pinchOff,
            hand = p[K.hand]?.let { runCatching { HandPreference.valueOf(it) }.getOrNull() } ?: d.hand,
            anchor = p[K.anchor]?.let { runCatching { CursorAnchor.valueOf(it) }.getOrNull() } ?: d.anchor,
            calibrated = p[K.calibrated] ?: d.calibrated,
        )
    }

    val flow: Flow<AppSettings> = store.data.map { read(it) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val n = transform(read(p))
            p[K.regionLeft] = n.regionLeft
            p[K.regionTop] = n.regionTop
            p[K.regionRight] = n.regionRight
            p[K.regionBottom] = n.regionBottom
            p[K.sensitivity] = n.sensitivity
            p[K.smoothing] = n.smoothing
            p[K.pinchOn] = n.pinchOn
            p[K.pinchOff] = n.pinchOff
            p[K.hand] = n.hand.name
            p[K.anchor] = n.anchor.name
            p[K.calibrated] = n.calibrated
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }
}
