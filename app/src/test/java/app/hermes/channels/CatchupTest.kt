package app.hermes.channels

import app.hermes.channels.core.Catchup
import app.hermes.channels.core.M3u
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchupTest {
    private val none = emptyMap<String, String>()

    @Test fun rules() {
        assertNull(Catchup.rule(none, none))
        assertNull(Catchup.rule(mapOf("tvg-rec" to "0"), none))
        Catchup.rule(mapOf("tvg-rec" to "3"), none)!!.let { assertEquals("shift", it.kind); assertEquals(3, it.days) }
        Catchup.rule(none, mapOf("catchup" to "flussonic", "catchup-days" to "7"))!!.let { assertEquals("flussonic", it.kind); assertEquals(7, it.days) }
        Catchup.rule(mapOf("catchup" to "fs"), none)!!.let { assertEquals("flussonic", it.kind); assertEquals(3, it.days) }
        Catchup.rule(mapOf("catchup-source" to "http://a/{utc}"), mapOf("catchup-days" to "2"))!!.let { assertEquals("default", it.kind); assertEquals(2, it.days) }
        assertNull(Catchup.rule(mapOf("catchup" to "shift", "catchup-days" to "0"), none))
        assertEquals(1, Catchup.rule(mapOf("catchup-time" to "3600"), none)!!.days)
    }

    @Test fun dates() {
        assertArrayEquals(intArrayOf(1970, 1, 1, 0, 0, 0), Catchup.parts(0))
        assertArrayEquals(intArrayOf(2026, 11, 15, 18, 30, 5), Catchup.parts(1794767405))
        assertArrayEquals(intArrayOf(2024, 2, 29, 23, 59, 59), Catchup.parts(1709251199))
    }

    @Test fun addresses() {
        val s = 1794767400L; val n = s + 7200
        fun r(kind: String, source: String = "") = Catchup.Rule(kind, source, 3)
        assertEquals("http://h/k/12/index.m3u8?utc=$s&lutc=$n", Catchup.address("http://h/k/12/index.m3u8", r("shift"), s, n, 3600))
        assertEquals("http://h/a.m3u8?t=1&utc=$s&lutc=$n", Catchup.address("http://h/a.m3u8?t=1", r("shift"), s, n, 3600))
        assertEquals("http://h/ch/index-$s-3600.m3u8?token=x", Catchup.address("http://h/ch/index.m3u8?token=x", r("flussonic"), s, n, 3600))
        assertEquals("http://h/ch/timeshift_abs-$s.ts", Catchup.address("http://h/ch/mpegts", r("flussonic"), s, n, 3600))
        assertEquals("http://h/a.m3u8?from=$s&to=${s + 3600}", Catchup.address("http://h/a.m3u8", r("append", "?from=\${start}&to=\${end}"), s, n, 3600))
        assertEquals("http://arc/x/2026/11/15/18-30.m3u8?d=3600&o=7200", Catchup.address("http://h/a.m3u8", r("default", "http://arc/x/{Y}/{m}/{d}/{H}-{M}.m3u8?d={duration}&o={offset}"), s, n, 3600))
        assertEquals("http://h:80/timeshift/u/p/60/2026-11-15:18-30/55.ts", Catchup.address("http://h:80/u/p/55", r("xc"), s, n, 3600))
        assertEquals("http://h/timeshift/u/p/60/2026-11-15:18-30/55.m3u8", Catchup.address("http://h/live/u/p/55.m3u8", r("xc"), s, n, 3600))
    }

    @Test fun playlistLines() {
        val p = M3u.parse("#EXTM3U catchup=\"shift\" catchup-days=\"5\"\n#EXTINF:0 tvg-rec=\"3\",One\nhttp://h/k/1/index.m3u8\n#EXTINF:0,Two\nhttp://h/k/2/index.m3u8\n#EXTINF:0 tvg-rec=\"0\",Three\nhttp://h/k/3/index.m3u8\n")
        assertEquals(3, p.channels[0].rec!!.days)
        assertEquals(5, p.channels[1].rec!!.days)
        assertNull(p.channels[2].rec)
        assertNull(M3u.parse("#EXTM3U\n#EXTINF:0,One\nhttp://h/1\n").channels[0].rec)
    }
}
