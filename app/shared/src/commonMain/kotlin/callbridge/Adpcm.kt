package callbridge

/** IMA/DVI ADPCM. State is carried across calls; [predictor]/[index] are the state before the next block. */
class Adpcm {
    var predictor = 0
        private set
    var index = 0
        private set

    fun reset(predictor: Int = 0, index: Int = 0) {
        this.predictor = predictor; this.index = index.coerceIn(0, 88)
    }

    /** Encodes [pcm] (even length) into pcm.size/2 bytes, low nibble first. */
    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray((pcm.size + 1) / 2)
        for (i in pcm.indices) {
            val nib = encodeSample(pcm[i].toInt())
            if (i and 1 == 0) out[i shr 1] = nib.toByte()
            else out[i shr 1] = (out[i shr 1].toInt() or (nib shl 4)).toByte()
        }
        return out
    }

    fun decode(data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xff
            out[i * 2] = decodeSample(b and 0x0f)
            out[i * 2 + 1] = decodeSample(b shr 4)
        }
        return out
    }

    private fun encodeSample(sample: Int): Int {
        val step = STEP[index]
        var diff = sample - predictor
        var nib = 0
        if (diff < 0) { nib = 8; diff = -diff }
        var delta = step shr 3
        if (diff >= step) { nib = nib or 4; diff -= step; delta += step }
        if (diff >= step shr 1) { nib = nib or 2; diff -= step shr 1; delta += step shr 1 }
        if (diff >= step shr 2) { nib = nib or 1; delta += step shr 2 }
        predictor = (if (nib and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
        index = (index + INDEX_ADJ[nib and 7]).coerceIn(0, 88)
        return nib
    }

    private fun decodeSample(nib: Int): Short {
        val step = STEP[index]
        var delta = step shr 3
        if (nib and 4 != 0) delta += step
        if (nib and 2 != 0) delta += step shr 1
        if (nib and 1 != 0) delta += step shr 2
        predictor = (if (nib and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
        index = (index + INDEX_ADJ[nib and 7]).coerceIn(0, 88)
        return predictor.toShort()
    }

    companion object {
        private val INDEX_ADJ = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )

        /** Decode a self-contained frame (state comes from its header). */
        fun decodeFrame(f: Frame): ShortArray = Adpcm().apply { reset(f.predictor, f.index) }.decode(f.payload)
    }
}
