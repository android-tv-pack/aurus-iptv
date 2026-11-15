package app.hermes.channels

import app.hermes.channels.core.Rate
import org.junit.Assert.assertEquals
import org.junit.Test

class RateTest {
    private val screen = floatArrayOf(59.94006f, 60.000004f, 50f, 29.97003f, 30.000002f, 25f, 23.976025f, 24.000002f)
    private fun picked(fps: Float) = Rate.pick(fps, screen).let { if (it < 0) 0f else screen[it] }
    private fun times(stepUs: Double, n: Int = 24, skip: Set<Int> = emptySet()) =
        (0 until n + skip.size).filter { it !in skip }.map { (1_000_000 + it * stepUs).toLong() }.shuffled(java.util.Random(1)).toLongArray()

    @Test fun ratesFromTimes() {
        assertEquals(25f, Rate.of(times(40000.0)), 0.01f)
        assertEquals(50f, Rate.of(times(20000.0)), 0.01f)
        assertEquals(23.976f, Rate.of(times(1_000_000 / 23.976)), 0.005f)
        assertEquals(24f, Rate.of(times(1_000_000 / 24.0)), 0.005f)
        assertEquals(59.94f, Rate.of(times(1_000_000 / 59.94)), 0.02f)
        // pictures the decoder threw away leave gaps: the usual step still decides
        assertEquals(50f, Rate.of(times(20000.0, skip = setOf(3, 4, 11))), 0.01f)
        // a channel that starts with every other picture left out for a while
        assertEquals(50f, Rate.of(times(20000.0, n = 12, skip = setOf(1, 3, 5, 7, 9))), 0.01f)
        // times in whole milliseconds with pictures left out: 33 ms stands for 30 a second
        assertEquals(30f, Rate.of(longArrayOf(0, 33, 66, 133, 166, 200, 233, 300, 333, 366, 400, 466, 500, 533).map { it * 1000 }.toLongArray()), 0.001f)
        assertEquals(43.3f, Rate.standard(43.3f), 0f)
        assertEquals(0f, Rate.of(LongArray(24) { 5L }), 0f)
        assertEquals(0f, Rate.of(longArrayOf(1, 2)), 0f)
    }

    @Test fun screenRates() {
        assertEquals(50f, picked(25f), 0f)
        assertEquals(50f, picked(50f), 0f)
        assertEquals(59.94006f, picked(29.97f), 0f)
        assertEquals(59.94006f, picked(59.94f), 0f)
        assertEquals(60.000004f, picked(30f), 0f)
        assertEquals(60.000004f, picked(29.99f), 0f)
        assertEquals(60.000004f, picked(60f), 0f)
        assertEquals(23.976025f, picked(23.976f), 0f)
        assertEquals(24.000002f, picked(24f), 0f)
        assertEquals(0f, picked(43.3f), 0f)                       // a channel that stumbles: the screen is left alone
        assertEquals(-1, Rate.pick(25f, floatArrayOf(60f)))
        assertEquals(1, Rate.pick(25f, floatArrayOf(60f, 25f)))
    }
}
