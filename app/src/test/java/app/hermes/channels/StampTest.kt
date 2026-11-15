package app.hermes.channels

import app.hermes.channels.core.Stamp
import org.junit.Assert.assertEquals
import org.junit.Test

class StampTest {
    @Test fun readsWhatItWrote() {
        val a = byteArrayOf(0x31, 0, 1, 0, 1)                 // the marker bits of a header, time 0
        for (t in longArrayOf(0, 1, 90000, 27488265L * 90, (1L shl 33) - 1)) {
            Stamp.write(a, 0, t)
            assertEquals(t, Stamp.read(a, 0))
            assertEquals(0x31, a[0].toInt() and 0xF1)          // the bits beside the time are kept
            assertEquals(1, a[2].toInt() and 1); assertEquals(1, a[4].toInt() and 1)
        }
    }
    @Test fun goesRoundTheEnd() {
        val a = ByteArray(5)
        Stamp.write(a, 0, -90000)
        assertEquals((1L shl 33) - 90000, Stamp.read(a, 0))
        assertEquals(180000, Stamp.diff(90000, (1L shl 33) - 90000))
        assertEquals(-180000, Stamp.diff((1L shl 33) - 90000, 90000))
        assertEquals(9602193L * 90, Stamp.diff(27488265L * 90, 17886072L * 90))
    }
}
