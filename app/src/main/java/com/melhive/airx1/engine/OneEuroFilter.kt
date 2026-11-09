package com.melhive.airx1.engine

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez et al., CHI 2012): strong smoothing when the hand is still,
 * low lag when it moves fast. [minCutoff] in Hz (lower = smoother), [beta] = speed coefficient.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 4.0,
    var dCutoff: Double = 1.0,
) {
    private var xPrev: Double? = null
    private var dxPrev = 0.0
    private var tPrev = 0.0

    fun reset() {
        xPrev = null
        dxPrev = 0.0
    }

    fun filter(x: Double, tSeconds: Double): Double {
        val prev = xPrev
        if (prev == null) {
            xPrev = x
            tPrev = tSeconds
            dxPrev = 0.0
            return x
        }
        val dt = (tSeconds - tPrev).coerceAtLeast(1e-3)
        tPrev = tSeconds
        val dx = (x - prev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        dxPrev = dxHat
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * prev
        xPrev = xHat
        return xHat
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
