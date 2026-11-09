package com.melhive.airx1.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {
    private val xs = List(60) { 0.3f + 0.4f * it / 59f }
    private val ys = List(60) { 0.25f + 0.5f * it / 59f }

    @Test fun computesThresholdsBetweenPinchedAndRelaxed() {
        val c = Calibrator.compute(List(30) { 1.0f }, List(30) { 0.1f }, xs, ys).getOrThrow()
        assertEquals(0.1f + 0.35f * 0.9f, c.pinchOn, 1e-4f)
        assertEquals(0.1f + 0.55f * 0.9f, c.pinchOff, 1e-4f)
        assertTrue(c.pinchOff > c.pinchOn)
        assertTrue(c.left > 0.3f && c.right < 0.7f && c.left < c.right)
        assertTrue(c.top > 0.25f && c.bottom < 0.75f && c.top < c.bottom)
    }

    @Test fun failsWhenHandNotSeen() {
        assertTrue(Calibrator.compute(List(5) { 1f }, List(30) { 0.1f }, xs, ys).isFailure)
    }

    @Test fun failsWhenPinchIndistinguishable() {
        assertTrue(Calibrator.compute(List(30) { 0.5f }, List(30) { 0.45f }, xs, ys).isFailure)
    }

    @Test fun failsWhenHandBarelyMoved() {
        assertTrue(Calibrator.compute(List(30) { 1f }, List(30) { 0.1f }, List(60) { 0.5f }, ys).isFailure)
    }
}
