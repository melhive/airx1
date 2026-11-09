package com.melhive.airx1.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class OneEuroFilterTest {
    @Test fun constantInputStaysConstant() {
        val f = OneEuroFilter()
        repeat(50) { i -> assertEquals(0.4, f.filter(0.4, i / 30.0), 1e-9) }
    }

    @Test fun reducesJitter() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 4.0)
        val rnd = Random(42)
        var rawVar = 0.0
        var outVar = 0.0
        for (i in 0 until 300) {
            val noise = (rnd.nextDouble() - 0.5) * 0.01
            val out = f.filter(0.5 + noise, i / 30.0)
            if (i > 30) {
                rawVar += noise * noise
                outVar += (out - 0.5) * (out - 0.5)
            }
        }
        assertTrue("filtered variance $outVar should be well below raw $rawVar", outVar < rawVar * 0.3)
    }

    @Test fun followsAStep() {
        val f = OneEuroFilter()
        f.filter(0.0, 0.0)
        var out = 0.0
        for (i in 1..30) out = f.filter(1.0, i / 30.0)
        assertTrue("after 1s the filter should be near the target, was $out", abs(out - 1.0) < 0.05)
    }
}
