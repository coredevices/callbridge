package callbridge

/** One 20 ms audio frame; see docs/protocol.md. Header is 6 bytes little-endian. */
class Frame(
    val seq: Int,          // u16
    val codec: Int,        // CODEC_ADPCM_8K / CODEC_ADPCM_16K
    val predictor: Int,    // i16
    val index: Int,        // u8
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val out = ByteArray(HEADER + payload.size)
        out[0] = seq.toByte(); out[1] = (seq shr 8).toByte()
        out[2] = codec.toByte()
        out[3] = predictor.toByte(); out[4] = (predictor shr 8).toByte()
        out[5] = index.toByte()
        payload.copyInto(out, HEADER)
        return out
    }

    val sampleRate: Int get() = if (codec == CODEC_ADPCM_16K) 16000 else 8000

    companion object {
        const val HEADER = 6
        const val CODEC_ADPCM_8K = 0x01
        const val CODEC_ADPCM_16K = 0x02
        const val CODEC_BLAST = 0xFF

        fun decode(b: ByteArray): Frame? {
            if (b.size < HEADER) return null
            return Frame(
                seq = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8),
                codec = b[2].toInt() and 0xff,
                predictor = ((b[3].toInt() and 0xff) or ((b[4].toInt() and 0xff) shl 8)).toShort().toInt(),
                index = b[5].toInt() and 0xff,
                payload = b.copyOfRange(HEADER, b.size),
            )
        }
    }
}
