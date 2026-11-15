package app.hermes.channels.core

/** The 33-bit times of a transport stream's packets (90,000 units a second), as they lie in five bytes of the packet's header. */
object Stamp {
    private const val MASK = (1L shl 33) - 1

    fun read(a: ByteArray, at: Int): Long =
        (((a[at].toLong() and 255L) shr 1 and 7L) shl 30) or ((a[at + 1].toLong() and 255L) shl 22) or (((a[at + 2].toLong() and 255L) shr 1) shl 15) or
            ((a[at + 3].toLong() and 255L) shl 7) or ((a[at + 4].toLong() and 255L) shr 1)

    /** writes [time] (taken round the 33 bits) and leaves the other bits of the five bytes as they are */
    fun write(a: ByteArray, at: Int, time: Long) {
        val v = time and MASK
        a[at] = ((a[at].toInt() and 0xF1) or (((v shr 30) and 7L).toInt() shl 1)).toByte()
        a[at + 1] = ((v shr 22) and 255L).toByte()
        a[at + 2] = ((a[at + 2].toInt() and 1) or (((v shr 15) and 127L).toInt() shl 1)).toByte()
        a[at + 3] = ((v shr 7) and 255L).toByte()
        a[at + 4] = ((a[at + 4].toInt() and 1) or ((v and 127L).toInt() shl 1)).toByte()
    }

    /** a - b, the short way round the 33 bits */
    fun diff(a: Long, b: Long): Long {
        var d = (a - b) and MASK
        if (d > MASK / 2) d -= MASK + 1
        return d
    }
}
