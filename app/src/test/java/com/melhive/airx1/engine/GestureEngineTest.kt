package com.melhive.airx1.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {
    private val frameMs = 33L

    private class Run(val engine: GestureEngine) {
        var now = 0L
        val events = mutableListOf<GestureEvent>()
        lateinit var last: EngineSnapshot
    }

    private fun Run.feed(frame: HandFrame?, durationMs: Long) {
        val end = now + durationMs
        while (now < end) {
            val out = engine.process(frame, now)
            events += out.events
            last = out.snapshot
            now += 33L
        }
    }

    private fun Run.arm() {
        feed(TestHands.point(), 300)
        feed(TestHands.fist(), 1100)
        feed(TestHands.point(), 700) // past the arm grace period
    }

    private fun newRun(config: EngineConfig = EngineConfig()) = Run(GestureEngine(config))
    private fun Run.taps() = events.filterIsInstance<GestureEvent.Tap>()
    private fun Run.armChanges() = events.filterIsInstance<GestureEvent.ArmChanged>()

    @Test fun startsDisarmedAndNeverTapsWhileDisarmed() {
        val r = newRun()
        r.feed(TestHands.point(), 300)
        r.feed(TestHands.pinch(), 150)
        r.feed(TestHands.point(), 300)
        assertEquals(EngineState.DISARMED, r.last.state)
        assertTrue(r.events.isEmpty())
    }

    @Test fun fistHoldArmsThenDisarms() {
        val r = newRun()
        r.feed(TestHands.fist(), 700)
        assertTrue("not armed before 1s", r.armChanges().isEmpty())
        r.feed(TestHands.fist(), 2000) // keep holding: latched, must not toggle again
        assertEquals(listOf(true), r.armChanges().map { it.armed })
        r.feed(TestHands.point(), 300)
        r.feed(TestHands.fist(), 1100)
        assertEquals(listOf(true, false), r.armChanges().map { it.armed })
        assertEquals(EngineState.DISARMED, r.last.state)
    }

    @Test fun quickPinchTapsAtPrePinchPosition() {
        val r = newRun()
        r.arm()
        val before = r.last
        r.feed(TestHands.pinch(), 150)
        assertEquals(EngineState.PINCHING, r.last.state)
        r.feed(TestHands.point(), 100)
        val taps = r.taps()
        assertEquals(1, taps.size)
        assertEquals(before.cursorX, taps[0].x, 0.01f)
        assertEquals(before.cursorY, taps[0].y, 0.01f)
    }

    @Test fun longPinchIsNotATap() {
        val r = newRun()
        r.arm()
        r.feed(TestHands.pinch(), 900)
        r.feed(TestHands.point(), 100)
        assertTrue(r.taps().isEmpty())
    }

    @Test fun tapsAreDebounced() {
        val r = newRun()
        r.arm()
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.point(), 66)
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.point(), 66)
        assertEquals(1, r.taps().size)
        r.feed(TestHands.point(), 300)
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.point(), 66)
        assertEquals(2, r.taps().size)
    }

    @Test fun pinchRightAfterArmingIsIgnored() {
        val r = newRun()
        r.feed(TestHands.fist(), 1100)
        r.feed(TestHands.pinch(), 100) // still inside the grace window
        r.feed(TestHands.point(), 100)
        assertTrue(r.taps().isEmpty())
    }

    @Test fun fistCancelsPinchWithoutTap() {
        val r = newRun()
        r.arm()
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.fist(), 200)
        r.feed(TestHands.point(), 100)
        assertTrue(r.taps().isEmpty())
    }

    @Test fun autoDisarmsAfterNoHand() {
        val r = newRun()
        r.arm()
        r.feed(null, 9000)
        assertTrue(r.last.armed)
        r.feed(null, 1200)
        assertFalse(r.last.armed)
        assertEquals("no hand for 10s", r.armChanges().last().reason)
    }

    @Test fun externalDisarm() {
        val r = newRun()
        r.arm()
        val e = r.engine.disarm("stop", r.now)
        assertEquals(listOf(GestureEvent.ArmChanged(false, "stop")), e)
        assertTrue(r.engine.disarm("stop again", r.now).isEmpty())
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.point(), 100)
        assertTrue(r.taps().isEmpty())
    }

    @Test fun wrongHandIsIgnored() {
        val r = newRun(EngineConfig(handPreference = HandPreference.LEFT))
        r.feed(TestHands.fist(), 1500) // TestHands are RIGHT
        assertTrue(r.events.isEmpty())
        assertFalse(r.last.handPresent)
    }

    @Test fun cursorFollowsHand() {
        val r = newRun()
        r.feed(TestHands.point(), 1000)
        val a = r.last.cursorX
        r.feed(TestHands.point(dx = 0.1f), 1000)
        assertTrue("cursor should move right", r.last.cursorX > a + 0.1f)
    }

    @Test fun activeRegionMapping() {
        val c = EngineConfig()
        fun check(ex: Float, ey: Float, p: Pair<Float, Float>) {
            assertEquals(ex, p.first, 1e-4f)
            assertEquals(ey, p.second, 1e-4f)
        }
        check(0.5f, 0.5f, GestureEngine.mapToScreen(0.5f, 0.5f, c))
        check(0f, 0f, GestureEngine.mapToScreen(0.2f, 0.2f, c))
        check(1f, 1f, GestureEngine.mapToScreen(0.8f, 0.8f, c))
        check(0f, 1f, GestureEngine.mapToScreen(0.0f, 0.99f, c)) // clamped
        val sensitive = c.copy(sensitivity = 2f)
        val (x, _) = GestureEngine.mapToScreen(0.65f, 0.5f, sensitive)
        assertEquals(1f, x, 1e-4f)
    }

    @Test fun mirrorFlipsX() {
        val c = EngineConfig(mirrorX = true)
        val r = Run(GestureEngine(c))
        r.feed(TestHands.point(dx = -0.1f), 1000)
        assertTrue("mirrored: hand on the left moves the cursor right", r.last.cursorX > 0.5f)
    }

    @Test fun sanitizedKeepsHysteresis() {
        val c = EngineConfig(pinchOn = 0.5f, pinchOff = 0.4f).sanitized()
        assertTrue(c.pinchOff > c.pinchOn)
    }
}
