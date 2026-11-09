package com.melhive.airx1.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long press, drag, navigation poses, launcher, dwell, scroll mode, precision. */
class GestureEngineFullTest {

    private class Run(val engine: GestureEngine) {
        var now = 0L
        val events = mutableListOf<GestureEvent>()
        lateinit var last: EngineSnapshot

        fun feed(frame: HandFrame?, durationMs: Long) {
            val end = now + durationMs
            while (now < end) {
                val out = engine.process(frame, now)
                events += out.events
                last = out.snapshot
                now += 33L
            }
        }

        fun arm() {
            feed(TestHands.point(), 300)
            feed(TestHands.fist(), 1100)
            feed(TestHands.point(), 700)
            events.clear()
        }

        inline fun <reified T : GestureEvent> of() = events.filterIsInstance<T>()
    }

    private fun armed(config: EngineConfig = EngineConfig()) = Run(GestureEngine(config)).apply { arm() }

    @Test fun longPressFiresOnceAndSuppressesTap() {
        val r = armed()
        r.feed(TestHands.pinch(), 1200)
        r.feed(TestHands.point(), 100)
        assertEquals(1, r.of<GestureEvent.LongPress>().size)
        assertTrue(r.of<GestureEvent.Tap>().isEmpty())
    }

    @Test fun pinchAndMoveDrags() {
        val r = armed()
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.pinch(dy = -0.1f), 400)
        assertEquals(EngineState.DRAGGING, r.last.state)
        r.feed(TestHands.point(dy = -0.1f), 100)
        assertEquals(1, r.of<GestureEvent.DragStart>().size)
        assertTrue(r.of<GestureEvent.DragMove>().isNotEmpty())
        val end = r.of<GestureEvent.DragEnd>()
        assertEquals(1, end.size)
        val start = r.of<GestureEvent.DragStart>()[0]
        assertTrue("drag should move up", end[0].y < start.y - 0.1f)
        assertTrue(r.of<GestureEvent.Tap>().isEmpty())
        assertTrue(r.of<GestureEvent.LongPress>().isEmpty())
    }

    @Test fun dragAlwaysEndsWhenHandIsLost() {
        val r = armed()
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.pinch(dy = -0.1f), 300)
        r.feed(null, 100)
        assertEquals(1, r.of<GestureEvent.DragEnd>().size)
    }

    @Test fun dragAlwaysEndsOnExternalDisarm() {
        val r = armed()
        r.feed(TestHands.pinch(), 100)
        r.feed(TestHands.pinch(dy = -0.1f), 300)
        val ev = r.engine.disarm("stop", r.now)
        assertTrue(ev.any { it is GestureEvent.DragEnd })
        assertTrue(ev.any { it is GestureEvent.ArmChanged })
    }

    private fun poseFires(frame: HandFrame, holdMs: Long, expected: GestureEvent) {
        val r = armed()
        r.feed(frame, holdMs + 100)
        assertEquals(listOf(expected), r.events)
        r.feed(frame, 2000) // latched: holding longer must not repeat
        assertEquals(1, r.events.size)
    }

    @Test fun twoFingersIsBack() = poseFires(TestHands.two(), 600, GestureEvent.GlobalAction(NavAction.BACK))
    @Test fun threeFingersIsHome() = poseFires(TestHands.three(), 600, GestureEvent.GlobalAction(NavAction.HOME))
    @Test fun fourFingersIsRecents() = poseFires(TestHands.four(), 600, GestureEvent.GlobalAction(NavAction.RECENTS))
    @Test fun openPalmOpensLauncher() = poseFires(TestHands.openPalm(), 1000, GestureEvent.OpenLauncher)

    @Test fun briefPoseDoesNothing() {
        val r = armed()
        r.feed(TestHands.two(), 300)
        r.feed(TestHands.point(), 300)
        assertTrue(r.events.isEmpty())
    }

    @Test fun posesNeedArming() {
        val r = Run(GestureEngine())
        r.feed(TestHands.two(), 1500)
        r.feed(TestHands.openPalm(), 1500)
        assertTrue(r.events.isEmpty())
    }

    @Test fun poseShowsProgressAndLabel() {
        val r = armed()
        r.feed(TestHands.three(), 300)
        assertEquals("Home", r.last.poseLabel)
        assertTrue(r.last.poseProgress in 0.3f..0.7f)
    }

    @Test fun dwellClicksWhenEnabled() {
        val r = armed(EngineConfig(dwellEnabled = true))
        r.feed(TestHands.point(), 1300)
        assertEquals(1, r.of<GestureEvent.Tap>().size)
        r.feed(TestHands.point(), 2000) // staying still does not repeat
        assertEquals(1, r.of<GestureEvent.Tap>().size)
    }

    @Test fun noDwellRightAfterPinchTap() {
        val r = armed(EngineConfig(dwellEnabled = true))
        r.feed(TestHands.point(dx = 0.1f), 500) // move first so the dwell anchor resets
        r.events.clear()
        r.feed(TestHands.point(dx = 0.1f), 300)
        r.feed(TestHands.pinch(dx = 0.1f), 100)
        r.feed(TestHands.point(dx = 0.1f), 2000)
        assertEquals(1, r.of<GestureEvent.Tap>().size)
    }

    @Test fun noDwellWhenDisabled() {
        val r = armed()
        r.feed(TestHands.point(), 3000)
        assertTrue(r.of<GestureEvent.Tap>().isEmpty())
    }

    @Test fun scrollModeScrollsByHandHeight() {
        val r = armed(EngineConfig(mode = InputMode.SCROLL))
        r.feed(TestHands.point(), 500) // centered: recenter, no scroll
        assertTrue(r.of<GestureEvent.Scroll>().isEmpty())
        r.feed(TestHands.point(dy = -0.25f), 100) // jump up (counts as a flick)
        r.events.clear()
        r.feed(TestHands.point(dy = -0.25f), 1500) // held high: but needs recenter after the flick
        assertTrue(r.of<GestureEvent.Scroll>().isEmpty())
        r.feed(TestHands.point(), 300) // recenter
        r.feed(TestHands.point(dy = -0.04f), 200)
        r.feed(TestHands.point(dy = -0.08f), 200)
        r.feed(TestHands.point(dy = -0.12f), 200)
        r.feed(TestHands.point(dy = -0.16f), 1500)
        val scrolls = r.of<GestureEvent.Scroll>()
        assertTrue("expected repeated scrolls, got ${scrolls.size}", scrolls.size >= 3)
        assertTrue(scrolls.all { it.direction == Direction.UP })
        assertTrue(r.of<GestureEvent.Tap>().isEmpty())
    }

    @Test fun scrollModeFlickSwipes() {
        val r = armed(EngineConfig(mode = InputMode.SCROLL))
        r.feed(TestHands.point(), 500)
        r.feed(TestHands.point(dy = -0.25f), 200)
        val swipes = r.of<GestureEvent.Swipe>()
        assertEquals(1, swipes.size)
        assertEquals(Direction.UP, swipes[0].direction)
    }

    @Test fun scrollModeIgnoresPinch() {
        val r = armed(EngineConfig(mode = InputMode.SCROLL))
        r.feed(TestHands.pinch(), 150)
        r.feed(TestHands.point(), 150)
        assertTrue(r.of<GestureEvent.Tap>().isEmpty())
        assertFalse(r.last.state == EngineState.PINCHING)
    }

    @Test fun precisionSlowsTheCursor() {
        val normal = Run(GestureEngine())
        normal.feed(TestHands.point(), 1000)
        val a = normal.last.cursorX
        normal.feed(TestHands.point(dx = 0.1f), 1000)
        val normalMove = normal.last.cursorX - a

        val precise = Run(GestureEngine()).apply { engine.precision = true }
        precise.feed(TestHands.point(), 1000)
        val b = precise.last.cursorX
        precise.feed(TestHands.point(dx = 0.1f), 1000)
        val preciseMove = precise.last.cursorX - b
        assertTrue("precision $preciseMove vs normal $normalMove", preciseMove < normalMove * 0.6f)
    }

    @Test fun fingertipDotsSurroundCursor() {
        val r = Run(GestureEngine())
        r.feed(TestHands.openPalm(), 500)
        assertEquals(5, r.last.fingertips.size)
        val index = r.last.fingertips[1]
        assertTrue("index tip dot above the cursor", index.y < r.last.cursorY)
    }
}
