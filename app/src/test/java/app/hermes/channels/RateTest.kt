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

    @Test fun unevenTimesAverageOut() {
        // 50 a second (ten pictures in 200 ms), but the times step by 16.7 ms for six pictures and 25 ms for four, over and over; one out of order
        val t = ArrayList<Long>(); var at = 0L
        while (t.size < 60) { repeat(6) { t.add(at); at += 16_667 }; repeat(4) { t.add(at); at += 25_000 } }
        val a = t.take(60).toLongArray(); val x = a[3]; a[3] = a[4]; a[4] = x
        val fps = Rate.of(a)
        assertEquals(50f, fps, 0.01f)
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

    private fun ms(vararg v: Int) = LongArray(v.size) { v[it] * 1000L }

    @Test fun earlyTakesTheStepOfPicturesSentOutOfOrder() {
        // 25 a second, sent out of the order of showing, the last ones leaving gaps
        val t = ms(0, 160, 80, 40, 120, 320, 240, 200, 280, 480, 400, 360, 440, 640, 560, 520, 600, 800, 720, 680, 760, 960, 880, 840, 920, 1120, 1040, 1000, 1080, 1280, 1200, 1160, 1240, 1440, 1360, 1320, 1400, 1600, 1520, 1480, 1560, 1760, 1680, 1640, 1720, 1920, 1840, 1800, 1880, 1960, 2000, 2160, 2080, 2040, 2120, 2320, 2240, 2200, 2280, 2480, 2400, 2360, 2440, 2640)
        assertEquals(25f, Rate.early(t), 0.01f)
    }

    @Test fun earlyCountsTwoHalvesWithOneTimeOnce() {
        val t = LongArray(64) { (it / 2) * 40_000L }
        assertEquals(25f, Rate.early(t), 0.01f)
    }

    @Test fun earlyTellsFilmRatesApart() {
        assertEquals(23.976f, Rate.early(LongArray(64) { it * 3753L * 100 / 9 + (it * 75L / 100) * 100 / 9 }), 0.001f)
        assertEquals(24f, Rate.early(LongArray(64) { it * 3750L * 100 / 9 }), 0.001f)
        assertEquals(59.94f, Rate.early(LongArray(64) { (it * 1501.5).toLong() * 100 / 9 }), 0.001f)
        assertEquals(50f, Rate.early(LongArray(64) { it * 20_000L }), 0.001f)
    }

    @Test fun earlySaysNothingWhenTheTimesAreFew() {
        // a time to every second picture of 25 a second: would read as 12.5
        assertEquals(0f, Rate.early(LongArray(64) { it * 80_000L }), 0f)
        // uneven
        assertEquals(0f, Rate.early(LongArray(64) { it * 40_000L + (if (it % 2 == 0) 0 else 15_000) }), 0f)
        assertEquals(0f, Rate.early(LongArray(10) { it * 40_000L }), 0f)
    }
}
