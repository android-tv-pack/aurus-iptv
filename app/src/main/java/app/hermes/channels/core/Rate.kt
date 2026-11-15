package app.hermes.channels.core

import kotlin.math.abs

/** How many pictures a second a channel has, and which of a screen's rates suits that. */
object Rate {

    /**
     * Pictures a second, from the times (in microseconds, any order) at which some pictures in a row are to be shown.
     * 0 when the times tell nothing. A few missing pictures do not spoil it: the usual step between two decides.
     */
    fun of(times: LongArray): Float {
        if (times.size < 4) return 0f
        val t = times.sortedArray()
        val steps = LongArray(t.size - 1) { t[it + 1] - t[it] }
        val sorted = steps.sortedArray()
        val median = sorted[sorted.size / 2]
        if (median <= 0L) return 0f
        // while a channel starts, pictures are often left out, so many steps are two or three pictures long:
        // the shortest step that comes up several times is the true one
        var usual = median
        for (st in sorted) {
            if (st * 20 < median) continue                                   // (two pictures with the same time)
            if (sorted.count { abs(it - st) * 8 <= st } >= maxOf(3, steps.size / 5)) { usual = st; break }
        }
        // when no picture is missing, the whole stretch gives the finer figure (23.976 against 24)
        val even = steps.all { abs(it - usual) * 8 <= usual }
        // Some channels give a time only to every few pictures and the ones between get made-up, uneven times (a channel of
        // 50 a second showed steps of 16.6 and 22.3 ms and was taken for 60). Over a long stretch the average is right
        // whatever the single steps are: it decides when it lies near a usual rate.
        if (!even && steps.size >= 30) {
            val mean = 1_000_000f * steps.size / (t.last() - t.first())
            val near = usualRates.minByOrNull { abs(it - mean) }!!
            if (abs(near - mean) <= near * 0.04f) return near
        }
        val fps = if (even) 1_000_000f * steps.size / (t.last() - t.first()) else standard(1_000_000f / usual)
        return if (fps in 10f..130f) fps else 0f
    }

    private val usualRates = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /** a roughly measured rate (one step between two pictures, some streams give times in whole milliseconds) as the usual rate it stands for */
    fun standard(fps: Float): Float {
        val near = usualRates.minByOrNull { abs(it - fps) } ?: return fps
        return if (abs(near - fps) <= near * 0.025f) near else fps
    }

    /**
     * Which of the screen's [rates] suits a channel of [fps] pictures a second: twice its rate when the screen has
     * that, else the rate itself. -1 = none suits.
     */
    fun pick(fps: Float, rates: FloatArray): Int {
        // twice the channel's rate first (25 a second is shown evenly at 50, and menus move better), else the rate itself
        for (times in intArrayOf(2, 1)) {
            var best = -1
            var bestErr = 0.3f
            for (i in rates.indices) {
                val err = abs(rates[i] - fps * times)
                if (err <= bestErr) { best = i; bestErr = err }
            }
            if (best >= 0) return best
        }
        return -1
    }
}
