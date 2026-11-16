package app.hermes.channels

import app.hermes.channels.core.Mp2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Layer II decoder against what ffmpeg makes of the same files (made with ffmpeg: a tone with noise). */
class Mp2Test {
    private val LIMIT = 16      // of 32768: the two decoders round differently
    private fun res(name: String) = javaClass.getResourceAsStream("/mp2/$name")!!.readBytes()

    private fun check(name: String, rate: Int, mono: Boolean = false) {
        val data = res("$name.mp2"); val want = res("$name.pcm")
        val dec = Mp2(); val out = ShortArray(Mp2.SAMPLES * 2)
        var off = 0; var n = 0; var worst = 0; var loud = 0
        while (off < data.size) {
            val took = dec.decode(data, off, data.size - off, out, 0)
            if (took == 0) break
            assertEquals(rate, dec.sampleRate)
            for (i in out.indices) {
                // (the file to compare with has one value for a mono sample, the decoder gives it to both channels)
                val p = (if (mono) n * Mp2.SAMPLES + i / 2 else n * Mp2.SAMPLES * 2 + i) * 2
                if (p + 1 >= want.size) break
                val w = ((want[p + 1].toInt() shl 8) or (want[p].toInt() and 0xFF)).toShort().toInt()
                val diff = kotlin.math.abs(w - out[i]); if (diff > worst) worst = diff
                if (kotlin.math.abs(w) > 1000) loud++
            }
            off += took; n++
        }
        assertTrue("$name: frames $n", n >= 5)
        assertEquals("$name: all of the file is frames", data.size, off)
        assertTrue("$name: there is sound to compare ($loud)", loud > 1000)
        assertTrue("$name: worst difference $worst", worst <= LIMIT)
    }

    @Test fun stereo() = check("stereo48", 48000)
    @Test fun stereoLowRate() = check("stereo48low", 48000)
    @Test fun mono() = check("mono44", 44100, true)
    @Test fun monoLowRate() = check("mono32", 32000, true)

    @Test fun otherSoundIsNotTaken() {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        assertEquals(0, Mp2.frameSize(mp3, 0, mp3.size))
        assertEquals(0, Mp2.frameSize(byteArrayOf(0, 0, 0, 0), 0, 4))
        assertEquals(576, Mp2.frameSize(byteArrayOf(0xFF.toByte(), 0xFD.toByte(), 0xA4.toByte(), 0x00), 0, 4))   // 192 kbit/s at 48 kHz
    }
}
