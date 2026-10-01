package callbridge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecTest {
    @Test
    fun adpcmRoundTripIsClose() {
        val pcm = ShortArray(160) { (sin(it * 2 * PI * 440 / 8000) * 8000).toInt().toShort() }
        val enc = Adpcm()
        val p0 = enc.predictor; val i0 = enc.index
        val data = enc.encode(pcm)
        assertEquals(80, data.size)
        val dec = Adpcm().apply { reset(p0, i0) }
        val out = dec.decode(data)
        // encoder and decoder must track identical state
        assertEquals(enc.predictor, dec.predictor); assertEquals(enc.index, dec.index)
        // skip the cold-start slew (step size ramps from 7); steady state should be close
        val maxErr = (40 until pcm.size).maxOf { abs(pcm[it] - out[it]) }
        assertTrue(maxErr < 1500, "max error $maxErr")
    }

    @Test
    fun frameRoundTrip() {
        val f = Frame(seq = 0xBEEF, codec = Frame.CODEC_ADPCM_8K, predictor = -1234, index = 42, payload = ByteArray(80) { it.toByte() })
        val d = Frame.decode(f.encode())!!
        assertEquals(0xBEEF, d.seq); assertEquals(-1234, d.predictor); assertEquals(42, d.index)
        assertTrue(f.payload.contentEquals(d.payload))
    }
}

class SeqTest {
    @Test
    fun lossAccountingHandlesWrapAndLate() {
        val s = Session(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        var lost = -1L
        fun step(last: Int, seq: Int): Int = s.accountForTest(last, seq) { lost = it }
        assertEquals(0, step(65535, 0)); assertEquals(0L, lost)      // wrap, no loss
        assertEquals(5, step(2, 5)); assertEquals(2L, lost)          // gap of 2
        assertEquals(100, step(100, 100)); assertEquals(0L, lost)    // duplicate
        assertEquals(100, step(100, 99)); assertEquals(0L, lost)     // late frame keeps last
    }
}
