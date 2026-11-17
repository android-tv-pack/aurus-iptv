package app.hermes.channels.core

/**
 * A decoder for MPEG audio Layer II ("MP2"), the sound of many broadcast channels. Some TVs have no decoder for it
 * of their own, and their channels would play without sound.
 *
 * It follows the Layer II decoder of pl_mpeg by Dominic Szablewski (MIT licence), which is based on kjmp2 by
 * Martin J. Fiedler, with the sample values scaled as the standard has them. MPEG-1 Layer II only: 32, 44.1 and 48 kHz, mono or stereo. The sound comes out as two channels
 * of 16 bits (a mono channel is given to both).
 */
class Mp2 {
    /** of the frame decoded last */
    var sampleRate = 0; private set

    private val alloc = Array(2) { IntArray(32) }             // row of the quantizer table, or -1 for "no bits"
    private val sfInfo = Array(2) { IntArray(32) }
    private val scale = Array(2) { Array(32) { IntArray(3) } }
    private val sample = Array(2) { Array(32) { IntArray(3) } }
    private val d = FloatArray(1024).also { WINDOW.copyInto(it, 0, 0, 512); WINDOW.copyInto(it, 512, 0, 512) }
    private val v = Array(2) { FloatArray(1024) }
    private val u = FloatArray(32)
    private var vPos = 0

    private var buf = ByteArray(0)
    private var bit = 0
    private var end = 0
    private fun read(n: Int): Int {
        var r = 0
        for (i in 0 until n) {
            val b = if (bit < end) (buf[bit shr 3].toInt() shr (7 - (bit and 7))) and 1 else 0
            r = (r shl 1) or b; bit++
        }
        return r
    }

    /** after a jump in the stream: what the frames before left behind is forgotten */
    fun reset() { for (c in v) c.fill(0f); vPos = 0 }

    /**
     * Decodes the frame that starts at [off]. Returns how many bytes it took (0: no whole Layer II frame there), and
     * writes its 1152 samples for each of two channels, left and right in turn, to [out] from [outOff] (2304 values).
     */
    fun decode(data: ByteArray, off: Int, len: Int, out: ShortArray, outOff: Int): Int {
        val size = frameSize(data, off, len)
        if (size == 0 || size > len) return 0
        buf = data; bit = off * 8 + 11; end = (off + size) * 8
        read(2); read(2)
        val crc = read(1) == 0
        val bitrate = read(4) - 1
        val rate = read(2)
        read(1); read(1)
        val mode = read(2)
        var bound = if (mode == JOINT) (read(2) + 1) shl 2 else { read(2); if (mode == MONO) 0 else 32 }
        read(4)
        if (crc) read(16)
        sampleRate = RATES[rate]

        val tab2 = STEP1[if (mode == MONO) 0 else 1][bitrate]
        var tab3 = STEP2[tab2][rate]
        val sblimit = tab3 and 63
        tab3 = tab3 shr 6
        if (bound > sblimit) bound = sblimit

        for (sb in 0 until bound) { alloc[0][sb] = allocation(sb, tab3); alloc[1][sb] = allocation(sb, tab3) }
        for (sb in bound until sblimit) { val a = allocation(sb, tab3); alloc[0][sb] = a; alloc[1][sb] = a }

        val channels = if (mode == MONO) 1 else 2
        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) if (alloc[ch][sb] >= 0) sfInfo[ch][sb] = read(2)
            if (mode == MONO) sfInfo[1][sb] = sfInfo[0][sb]
        }
        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) if (alloc[ch][sb] >= 0) {
                val sf = scale[ch][sb]
                when (sfInfo[ch][sb]) {
                    0 -> { sf[0] = read(6); sf[1] = read(6); sf[2] = read(6) }
                    1 -> { sf[0] = read(6); sf[1] = sf[0]; sf[2] = read(6) }
                    2 -> { sf[0] = read(6); sf[1] = sf[0]; sf[2] = sf[0] }
                    else -> { sf[0] = read(6); sf[1] = read(6); sf[2] = sf[1] }
                }
            }
            if (mode == MONO) { scale[1][sb][0] = scale[0][sb][0]; scale[1][sb][1] = scale[0][sb][1]; scale[1][sb][2] = scale[0][sb][2] }
        }

        var pos = 0
        for (part in 0 until 3) for (granule in 0 until 4) {
            for (sb in 0 until bound) { samples(0, sb, part); samples(1, sb, part) }
            for (sb in bound until sblimit) {
                samples(0, sb, part)
                sample[1][sb][0] = sample[0][sb][0]; sample[1][sb][1] = sample[0][sb][1]; sample[1][sb][2] = sample[0][sb][2]
            }
            for (sb in sblimit until 32) for (ch in 0 until 2) { sample[ch][sb][0] = 0; sample[ch][sb][1] = 0; sample[ch][sb][2] = 0 }

            for (p in 0 until 3) {
                vPos = (vPos - 64) and 1023
                for (ch in 0 until 2) {
                    idct36(sample[ch], p, v[ch], vPos)
                    u.fill(0f)
                    var di = 512 - (vPos shr 1)
                    var vi = (vPos % 128) shr 1
                    val vc = v[ch]
                    while (vi < 1024) {
                        for (i in 0 until 32) u[i] += d[di++] * vc[vi++]
                        vi += 128 - 32; di += 64 - 32
                    }
                    di -= 512 - 32
                    vi = (128 - 32 + 1024) - vi
                    while (vi < 1024) {
                        for (i in 0 until 32) u[i] += d[di++] * vc[vi++]
                        vi += 128 - 32; di += 64 - 32
                    }
                    for (j in 0 until 32) {
                        val s = (u[j] / -32768f).toInt()
                        out[outOff + ((pos + j) shl 1) + ch] = (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
                    }
                }
                pos += 32
            }
        }
        return size
    }

    private fun allocation(sb: Int, tab3: Int): Int {
        val tab4 = STEP3[tab3][sb]
        return STEP4[tab4 and 15][read(tab4 shr 4)] - 1
    }

    private fun samples(ch: Int, sb: Int, part: Int) {
        val q = alloc[ch][sb]
        val s = sample[ch][sb]
        if (q < 0) { s[0] = 0; s[1] = 0; s[2] = 0; return }
        var sf = scale[ch][sb][part]
        sf = if (sf == 63) 0 else { val shift = sf / 3; (SCALE_BASE[sf % 3] + ((1 shl shift) shr 1)) shr shift }
        var adj = LEVELS[q]
        val bits = BITS[q]
        if (GROUP[q]) {
            var x = read(bits)
            s[0] = x % adj; x /= adj
            s[1] = x % adj
            s[2] = x / adj
        } else { s[0] = read(bits); s[1] = read(bits); s[2] = read(bits) }
        // (the value's place among the levels, as a fraction of full scale: steps of 2 / levels, as the standard
        // has it. The decoder this follows divided by levels + 1, which left coarsely coded bands too quiet.)
        val levels = adj
        adj = ((adj + 1) shr 1) - 1
        for (i in 0 until 3) {
            val x = (adj - s[i]) * 65536 / levels
            s[i] = (x * (sf shr 12) + ((x * (sf and 4095) + 2048) shr 12)) shr 12
        }
    }

    private fun idct36(s: Array<IntArray>, ss: Int, d: FloatArray, dp: Int) {
        var t01: Float; var t02: Float; var t03: Float; var t04: Float; var t05: Float; var t06: Float; var t07: Float; var t08: Float
        var t09: Float; var t10: Float; var t11: Float; var t12: Float; var t13: Float; var t14: Float; var t15: Float; var t16: Float
        var t17: Float; var t18: Float; var t19: Float; var t20: Float; var t21: Float; var t22: Float; var t23: Float; var t24: Float
        var t25: Float; var t26: Float; var t27: Float; var t28: Float; var t29: Float; var t30: Float; var t31: Float; var t32: Float
        var t33: Float
        t01 = (s[0][ss] + s[31][ss]).toFloat()
        t02 = (s[0][ss] - s[31][ss]).toFloat() * 0.500602998235f
        t03 = (s[1][ss] + s[30][ss]).toFloat()
        t04 = (s[1][ss] - s[30][ss]).toFloat() * 0.505470959898f
        t05 = (s[2][ss] + s[29][ss]).toFloat()
        t06 = (s[2][ss] - s[29][ss]).toFloat() * 0.515447309923f
        t07 = (s[3][ss] + s[28][ss]).toFloat()
        t08 = (s[3][ss] - s[28][ss]).toFloat() * 0.53104259109f
        t09 = (s[4][ss] + s[27][ss]).toFloat()
        t10 = (s[4][ss] - s[27][ss]).toFloat() * 0.553103896034f
        t11 = (s[5][ss] + s[26][ss]).toFloat()
        t12 = (s[5][ss] - s[26][ss]).toFloat() * 0.582934968206f
        t13 = (s[6][ss] + s[25][ss]).toFloat()
        t14 = (s[6][ss] - s[25][ss]).toFloat() * 0.622504123036f
        t15 = (s[7][ss] + s[24][ss]).toFloat()
        t16 = (s[7][ss] - s[24][ss]).toFloat() * 0.674808341455f
        t17 = (s[8][ss] + s[23][ss]).toFloat()
        t18 = (s[8][ss] - s[23][ss]).toFloat() * 0.744536271002f
        t19 = (s[9][ss] + s[22][ss]).toFloat()
        t20 = (s[9][ss] - s[22][ss]).toFloat() * 0.839349645416f
        t21 = (s[10][ss] + s[21][ss]).toFloat()
        t22 = (s[10][ss] - s[21][ss]).toFloat() * 0.972568237862f
        t23 = (s[11][ss] + s[20][ss]).toFloat()
        t24 = (s[11][ss] - s[20][ss]).toFloat() * 1.16943993343f
        t25 = (s[12][ss] + s[19][ss]).toFloat()
        t26 = (s[12][ss] - s[19][ss]).toFloat() * 1.48416461631f
        t27 = (s[13][ss] + s[18][ss]).toFloat()
        t28 = (s[13][ss] - s[18][ss]).toFloat() * 2.05778100995f
        t29 = (s[14][ss] + s[17][ss]).toFloat()
        t30 = (s[14][ss] - s[17][ss]).toFloat() * 3.40760841847f
        t31 = (s[15][ss] + s[16][ss]).toFloat()
        t32 = (s[15][ss] - s[16][ss]).toFloat() * 10.1900081235f
        t33 = t01 + t31
        t31 = (t01 - t31) * 0.502419286188f
        t01 = t03 + t29
        t29 = (t03 - t29) * 0.52249861494f
        t03 = t05 + t27
        t27 = (t05 - t27) * 0.566944034816f
        t05 = t07 + t25
        t25 = (t07 - t25) * 0.64682178336f
        t07 = t09 + t23
        t23 = (t09 - t23) * 0.788154623451f
        t09 = t11 + t21
        t21 = (t11 - t21) * 1.06067768599f
        t11 = t13 + t19
        t19 = (t13 - t19) * 1.72244709824f
        t13 = t15 + t17
        t17 = (t15 - t17) * 5.10114861869f
        t15 = t33 + t13
        t13 = (t33 - t13) * 0.509795579104f
        t33 = t01 + t11
        t01 = (t01 - t11) * 0.601344886935f
        t11 = t03 + t09
        t09 = (t03 - t09) * 0.899976223136f
        t03 = t05 + t07
        t07 = (t05 - t07) * 2.56291544774f
        t05 = t15 + t03
        t15 = (t15 - t03) * 0.541196100146f
        t03 = t33 + t11
        t11 = (t33 - t11) * 1.30656296488f
        t33 = t05 + t03
        t05 = (t05 - t03) * 0.707106781187f
        t03 = t15 + t11
        t15 = (t15 - t11) * 0.707106781187f
        t03 += t15
        t11 = t13 + t07
        t13 = (t13 - t07) * 0.541196100146f
        t07 = t01 + t09
        t09 = (t01 - t09) * 1.30656296488f
        t01 = t11 + t07
        t07 = (t11 - t07) * 0.707106781187f
        t11 = t13 + t09
        t13 = (t13 - t09) * 0.707106781187f
        t11 += t13
        t01 += t11
        t11 += t07
        t07 += t13
        t09 = t31 + t17
        t31 = (t31 - t17) * 0.509795579104f
        t17 = t29 + t19
        t29 = (t29 - t19) * 0.601344886935f
        t19 = t27 + t21
        t21 = (t27 - t21) * 0.899976223136f
        t27 = t25 + t23
        t23 = (t25 - t23) * 2.56291544774f
        t25 = t09 + t27
        t09 = (t09 - t27) * 0.541196100146f
        t27 = t17 + t19
        t19 = (t17 - t19) * 1.30656296488f
        t17 = t25 + t27
        t27 = (t25 - t27) * 0.707106781187f
        t25 = t09 + t19
        t19 = (t09 - t19) * 0.707106781187f
        t25 += t19
        t09 = t31 + t23
        t31 = (t31 - t23) * 0.541196100146f
        t23 = t29 + t21
        t21 = (t29 - t21) * 1.30656296488f
        t29 = t09 + t23
        t23 = (t09 - t23) * 0.707106781187f
        t09 = t31 + t21
        t31 = (t31 - t21) * 0.707106781187f
        t09 += t31
        t29 += t09
        t09 += t23
        t23 += t31
        t17 += t29
        t29 += t25
        t25 += t09
        t09 += t27
        t27 += t23
        t23 += t19
        t19 += t31
        t21 = t02 + t32
        t02 = (t02 - t32) * 0.502419286188f
        t32 = t04 + t30
        t04 = (t04 - t30) * 0.52249861494f
        t30 = t06 + t28
        t28 = (t06 - t28) * 0.566944034816f
        t06 = t08 + t26
        t08 = (t08 - t26) * 0.64682178336f
        t26 = t10 + t24
        t10 = (t10 - t24) * 0.788154623451f
        t24 = t12 + t22
        t22 = (t12 - t22) * 1.06067768599f
        t12 = t14 + t20
        t20 = (t14 - t20) * 1.72244709824f
        t14 = t16 + t18
        t16 = (t16 - t18) * 5.10114861869f
        t18 = t21 + t14
        t14 = (t21 - t14) * 0.509795579104f
        t21 = t32 + t12
        t32 = (t32 - t12) * 0.601344886935f
        t12 = t30 + t24
        t24 = (t30 - t24) * 0.899976223136f
        t30 = t06 + t26
        t26 = (t06 - t26) * 2.56291544774f
        t06 = t18 + t30
        t18 = (t18 - t30) * 0.541196100146f
        t30 = t21 + t12
        t12 = (t21 - t12) * 1.30656296488f
        t21 = t06 + t30
        t30 = (t06 - t30) * 0.707106781187f
        t06 = t18 + t12
        t12 = (t18 - t12) * 0.707106781187f
        t06 += t12
        t18 = t14 + t26
        t26 = (t14 - t26) * 0.541196100146f
        t14 = t32 + t24
        t24 = (t32 - t24) * 1.30656296488f
        t32 = t18 + t14
        t14 = (t18 - t14) * 0.707106781187f
        t18 = t26 + t24
        t24 = (t26 - t24) * 0.707106781187f
        t18 += t24
        t32 += t18
        t18 += t14
        t26 = t14 + t24
        t14 = t02 + t16
        t02 = (t02 - t16) * 0.509795579104f
        t16 = t04 + t20
        t04 = (t04 - t20) * 0.601344886935f
        t20 = t28 + t22
        t22 = (t28 - t22) * 0.899976223136f
        t28 = t08 + t10
        t10 = (t08 - t10) * 2.56291544774f
        t08 = t14 + t28
        t14 = (t14 - t28) * 0.541196100146f
        t28 = t16 + t20
        t20 = (t16 - t20) * 1.30656296488f
        t16 = t08 + t28
        t28 = (t08 - t28) * 0.707106781187f
        t08 = t14 + t20
        t20 = (t14 - t20) * 0.707106781187f
        t08 += t20
        t14 = t02 + t10
        t02 = (t02 - t10) * 0.541196100146f
        t10 = t04 + t22
        t22 = (t04 - t22) * 1.30656296488f
        t04 = t14 + t10
        t10 = (t14 - t10) * 0.707106781187f
        t14 = t02 + t22
        t02 = (t02 - t22) * 0.707106781187f
        t14 += t02
        t04 += t14
        t14 += t10
        t10 += t02
        t16 += t04
        t04 += t08
        t08 += t14
        t14 += t28
        t28 += t10
        t10 += t20
        t20 += t02
        t21 += t16
        t16 += t32
        t32 += t04
        t04 += t06
        t06 += t08
        t08 += t18
        t18 += t14
        t14 += t30
        t30 += t28
        t28 += t26
        t26 += t10
        t10 += t12
        t12 += t20
        t20 += t24
        t24 += t02
        d[dp + 48] = -t33
        d[dp + 49] = -t21; d[dp + 47] = -t21
        d[dp + 50] = -t17; d[dp + 46] = -t17
        d[dp + 51] = -t16; d[dp + 45] = -t16
        d[dp + 52] = -t01; d[dp + 44] = -t01
        d[dp + 53] = -t32; d[dp + 43] = -t32
        d[dp + 54] = -t29; d[dp + 42] = -t29
        d[dp + 55] = -t04; d[dp + 41] = -t04
        d[dp + 56] = -t03; d[dp + 40] = -t03
        d[dp + 57] = -t06; d[dp + 39] = -t06
        d[dp + 58] = -t25; d[dp + 38] = -t25
        d[dp + 59] = -t08; d[dp + 37] = -t08
        d[dp + 60] = -t11; d[dp + 36] = -t11
        d[dp + 61] = -t18; d[dp + 35] = -t18
        d[dp + 62] = -t09; d[dp + 34] = -t09
        d[dp + 63] = -t14; d[dp + 33] = -t14
        d[dp + 32] = -t05
        d[dp + 0] = t05
        d[dp + 31] = -t30
        d[dp + 1] = t30
        d[dp + 30] = -t27
        d[dp + 2] = t27
        d[dp + 29] = -t28
        d[dp + 3] = t28
        d[dp + 28] = -t07
        d[dp + 4] = t07
        d[dp + 27] = -t26
        d[dp + 5] = t26
        d[dp + 26] = -t23
        d[dp + 6] = t23
        d[dp + 25] = -t10
        d[dp + 7] = t10
        d[dp + 24] = -t15
        d[dp + 8] = t15
        d[dp + 23] = -t12
        d[dp + 9] = t12
        d[dp + 22] = -t19
        d[dp + 10] = t19
        d[dp + 21] = -t20
        d[dp + 11] = t20
        d[dp + 20] = -t13
        d[dp + 12] = t13
        d[dp + 19] = -t24
        d[dp + 13] = t24
        d[dp + 18] = -t31
        d[dp + 14] = t31
        d[dp + 17] = -t02
        d[dp + 15] = t02
        d[dp + 16] = 0f
    }

    companion object {
        const val SAMPLES = 1152
        private const val JOINT = 1
        private const val MONO = 3
        private val RATES = intArrayOf(44100, 48000, 32000, 0)
        private val BITRATES = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val SCALE_BASE = intArrayOf(0x02000000, 0x01965FEA, 0x01428A30)
        private val STEP1 = arrayOf(
            intArrayOf(0, 0, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2),
            intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 2))
        private const val A = 27 or 64
        private const val B = 30 or 64
        private const val C = 8
        private const val D = 12
        private val STEP2 = arrayOf(intArrayOf(C, C, D), intArrayOf(A, A, A), intArrayOf(B, A, B))
        private val STEP3 = arrayOf(
            intArrayOf(0x44, 0x44, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34, 0x34) + IntArray(20),
            intArrayOf(0x43, 0x43, 0x43, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
                0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31, 0x31,
                0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20) + IntArray(2))
        private val STEP4 = arrayOf(
            intArrayOf(0, 1, 2, 17) + IntArray(12),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 17) + IntArray(8),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17),
            intArrayOf(0, 1, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
            intArrayOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17),
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
        private val LEVELS = intArrayOf(3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535)
        private val GROUP = booleanArrayOf(true, true, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false)
        private val BITS = intArrayOf(5, 7, 3, 10, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)

        /**
         * How many bytes the MPEG-1 Layer II frame at [off] takes, or 0 when none starts there (another kind of
         * MPEG audio, or not the start of a frame).
         */
        fun frameSize(data: ByteArray, off: Int, len: Int): Int {
            if (len < 4) return 0
            val b1 = data[off + 1].toInt() and 0xFF; val b2 = data[off + 2].toInt() and 0xFF
            if ((data[off].toInt() and 0xFF) != 0xFF || (b1 and 0xFE) != 0xFC) return 0      // sync, MPEG-1, Layer II
            val bitrate = (b2 shr 4) - 1
            val rate = (b2 shr 2) and 3
            if (bitrate < 0 || bitrate > 13 || rate == 3) return 0
            return 144000 * BITRATES[bitrate] / RATES[rate] + ((b2 shr 1) and 1)
        }

        /** the sample rate the frame at [off] declares, or 0 */
        fun rateOf(data: ByteArray, off: Int, len: Int): Int = if (frameSize(data, off, len) == 0) 0 else RATES[((data[off + 2].toInt() and 0xFF) shr 2) and 3]

        private val WINDOW = floatArrayOf(
            0.0f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -1.0f, -1.0f, -1.0f, -1.0f, -1.5f,
            -1.5f, -2.0f, -2.0f, -2.5f, -2.5f, -3.0f, -3.5f, -3.5f, -4.0f, -4.5f, -5.0f, -5.5f,
            -6.5f, -7.0f, -8.0f, -8.5f, -9.5f, -10.5f, -12.0f, -13.0f, -14.5f, -15.5f, -17.5f, -19.0f,
            -20.5f, -22.5f, -24.5f, -26.5f, -29.0f, -31.5f, -34.0f, -36.5f, -39.5f, -42.5f, -45.5f, -48.5f,
            -52.0f, -55.5f, -58.5f, -62.5f, -66.0f, -69.5f, -73.5f, -77.0f, -80.5f, -84.5f, -88.0f, -91.5f,
            -95.0f, -98.0f, -101.0f, -104.0f, 106.5f, 109.0f, 111.0f, 112.5f, 113.5f, 114.0f, 114.0f, 113.5f,
            112.0f, 110.5f, 107.5f, 104.0f, 100.0f, 94.5f, 88.5f, 81.5f, 73.0f, 63.5f, 53.0f, 41.5f,
            28.5f, 14.5f, -1.0f, -18.0f, -36.0f, -55.5f, -76.5f, -98.5f, -122.0f, -147.0f, -173.5f, -200.5f,
            -229.5f, -259.5f, -290.5f, -322.5f, -355.5f, -389.5f, -424.0f, -459.5f, -495.5f, -532.0f, -568.5f, -605.0f,
            -641.5f, -678.0f, -714.0f, -749.0f, -783.5f, -817.0f, -849.0f, -879.5f, -908.5f, -935.0f, -959.5f, -981.0f,
            -1000.5f, -1016.0f, -1028.5f, -1037.5f, -1042.5f, -1043.5f, -1040.0f, -1031.5f, 1018.5f, 1000.0f, 976.0f, 946.5f,
            911.0f, 869.5f, 822.0f, 767.5f, 707.0f, 640.0f, 565.5f, 485.0f, 397.0f, 302.5f, 201.0f, 92.5f,
            -22.5f, -144.0f, -272.5f, -407.0f, -547.5f, -694.0f, -846.0f, -1003.0f, -1165.0f, -1331.5f, -1502.0f, -1675.5f,
            -1852.5f, -2031.5f, -2212.5f, -2394.0f, -2576.5f, -2758.5f, -2939.5f, -3118.5f, -3294.5f, -3467.5f, -3635.5f, -3798.5f,
            -3955.0f, -4104.5f, -4245.5f, -4377.5f, -4499.0f, -4609.5f, -4708.0f, -4792.5f, -4863.5f, -4919.0f, -4958.0f, -4979.5f,
            -4983.0f, -4967.5f, -4931.5f, -4875.0f, -4796.0f, -4694.5f, -4569.5f, -4420.0f, -4246.0f, -4046.0f, -3820.0f, -3567.0f,
            3287.0f, 2979.5f, 2644.0f, 2280.5f, 1888.0f, 1467.5f, 1018.5f, 541.0f, 35.0f, -499.0f, -1061.0f, -1650.0f,
            -2266.5f, -2909.0f, -3577.0f, -4270.0f, -4987.5f, -5727.5f, -6490.0f, -7274.0f, -8077.5f, -8899.5f, -9739.0f, -10594.5f,
            -11464.5f, -12347.0f, -13241.0f, -14144.5f, -15056.0f, -15973.5f, -16895.5f, -17820.0f, -18744.5f, -19668.0f, -20588.0f, -21503.0f,
            -22410.5f, -23308.5f, -24195.0f, -25068.5f, -25926.5f, -26767.0f, -27589.0f, -28389.0f, -29166.5f, -29919.0f, -30644.5f, -31342.0f,
            -32009.5f, -32645.0f, -33247.0f, -33814.5f, -34346.0f, -34839.5f, -35295.0f, -35710.0f, -36084.5f, -36417.5f, -36707.5f, -36954.0f,
            -37156.5f, -37315.0f, -37428.0f, -37496.0f, 37519.0f, 37496.0f, 37428.0f, 37315.0f, 37156.5f, 36954.0f, 36707.5f, 36417.5f,
            36084.5f, 35710.0f, 35295.0f, 34839.5f, 34346.0f, 33814.5f, 33247.0f, 32645.0f, 32009.5f, 31342.0f, 30644.5f, 29919.0f,
            29166.5f, 28389.0f, 27589.0f, 26767.0f, 25926.5f, 25068.5f, 24195.0f, 23308.5f, 22410.5f, 21503.0f, 20588.0f, 19668.0f,
            18744.5f, 17820.0f, 16895.5f, 15973.5f, 15056.0f, 14144.5f, 13241.0f, 12347.0f, 11464.5f, 10594.5f, 9739.0f, 8899.5f,
            8077.5f, 7274.0f, 6490.0f, 5727.5f, 4987.5f, 4270.0f, 3577.0f, 2909.0f, 2266.5f, 1650.0f, 1061.0f, 499.0f,
            -35.0f, -541.0f, -1018.5f, -1467.5f, -1888.0f, -2280.5f, -2644.0f, -2979.5f, 3287.0f, 3567.0f, 3820.0f, 4046.0f,
            4246.0f, 4420.0f, 4569.5f, 4694.5f, 4796.0f, 4875.0f, 4931.5f, 4967.5f, 4983.0f, 4979.5f, 4958.0f, 4919.0f,
            4863.5f, 4792.5f, 4708.0f, 4609.5f, 4499.0f, 4377.5f, 4245.5f, 4104.5f, 3955.0f, 3798.5f, 3635.5f, 3467.5f,
            3294.5f, 3118.5f, 2939.5f, 2758.5f, 2576.5f, 2394.0f, 2212.5f, 2031.5f, 1852.5f, 1675.5f, 1502.0f, 1331.5f,
            1165.0f, 1003.0f, 846.0f, 694.0f, 547.5f, 407.0f, 272.5f, 144.0f, 22.5f, -92.5f, -201.0f, -302.5f,
            -397.0f, -485.0f, -565.5f, -640.0f, -707.0f, -767.5f, -822.0f, -869.5f, -911.0f, -946.5f, -976.0f, -1000.0f,
            1018.5f, 1031.5f, 1040.0f, 1043.5f, 1042.5f, 1037.5f, 1028.5f, 1016.0f, 1000.5f, 981.0f, 959.5f, 935.0f,
            908.5f, 879.5f, 849.0f, 817.0f, 783.5f, 749.0f, 714.0f, 678.0f, 641.5f, 605.0f, 568.5f, 532.0f,
            495.5f, 459.5f, 424.0f, 389.5f, 355.5f, 322.5f, 290.5f, 259.5f, 229.5f, 200.5f, 173.5f, 147.0f,
            122.0f, 98.5f, 76.5f, 55.5f, 36.0f, 18.0f, 1.0f, -14.5f, -28.5f, -41.5f, -53.0f, -63.5f,
            -73.0f, -81.5f, -88.5f, -94.5f, -100.0f, -104.0f, -107.5f, -110.5f, -112.0f, -113.5f, -114.0f, -114.0f,
            -113.5f, -112.5f, -111.0f, -109.0f, 106.5f, 104.0f, 101.0f, 98.0f, 95.0f, 91.5f, 88.0f, 84.5f,
            80.5f, 77.0f, 73.5f, 69.5f, 66.0f, 62.5f, 58.5f, 55.5f, 52.0f, 48.5f, 45.5f, 42.5f,
            39.5f, 36.5f, 34.0f, 31.5f, 29.0f, 26.5f, 24.5f, 22.5f, 20.5f, 19.0f, 17.5f, 15.5f,
            14.5f, 13.0f, 12.0f, 10.5f, 9.5f, 8.5f, 8.0f, 7.0f, 6.5f, 5.5f, 5.0f, 4.5f,
            4.0f, 3.5f, 3.5f, 3.0f, 2.5f, 2.5f, 2.0f, 2.0f, 1.5f, 1.5f, 1.0f, 1.0f,
            1.0f, 1.0f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    }
}
