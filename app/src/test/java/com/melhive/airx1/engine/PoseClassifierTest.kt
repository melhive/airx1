package com.melhive.airx1.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseClassifierTest {
    private fun classify(f: HandFrame) = PoseClassifier.classify(f.landmarks, f.aspect)

    @Test fun fist() = assertEquals(HandPose.FIST, classify(TestHands.fist()))
    @Test fun point() = assertEquals(HandPose.POINT, classify(TestHands.point()))
    @Test fun two() = assertEquals(HandPose.TWO, classify(TestHands.two()))
    @Test fun three() = assertEquals(HandPose.THREE, classify(TestHands.three()))
    @Test fun four() = assertEquals(HandPose.FOUR, classify(TestHands.four()))
    @Test fun openPalm() = assertEquals(HandPose.OPEN_PALM, classify(TestHands.openPalm()))

    @Test fun pinchIsNotAFist() {
        assertNotEquals(HandPose.FIST, classify(TestHands.pinch()))
    }

    @Test fun pinchRatioIsSmallOnlyWhenPinching() {
        fun ratio(f: HandFrame) = f.landmarks.dist(Lm.THUMB_TIP, Lm.INDEX_TIP, 1f) /
            f.landmarks.dist(Lm.WRIST, Lm.MIDDLE_MCP, 1f)
        assertTrue(ratio(TestHands.pinch()) < 0.3f)
        assertTrue(ratio(TestHands.point()) > 0.6f)
    }

    @Test fun tooFewLandmarksIsNone() {
        assertEquals(HandPose.NONE, PoseClassifier.classify(emptyList(), 1f))
    }
}
