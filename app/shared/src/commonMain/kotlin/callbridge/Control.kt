package callbridge

/** Control characteristic messages; first byte is the type. */
object Control {
    const val CALL_STATE = 0x01
    const val CODEC = 0x02
    const val STATS = 0x03
    const val PONG = 0x04
    const val ANSWER = 0x10
    const val HANGUP = 0x11
    const val LOOPBACK = 0x12
    const val DIAL = 0x13
    const val REQUEST_STATS = 0x14
    const val PING = 0x15
    const val BLAST = 0x16

    val callStateNames = listOf("idle", "incoming", "dialing", "active", "sco_up")

    fun answer() = byteArrayOf(ANSWER.toByte())
    fun hangup() = byteArrayOf(HANGUP.toByte())
    fun loopback(on: Boolean) = byteArrayOf(LOOPBACK.toByte(), if (on) 1 else 0)
    fun dial(number: String) = byteArrayOf(DIAL.toByte()) + number.encodeToByteArray()
    fun requestStats() = byteArrayOf(REQUEST_STATS.toByte())
    /** Throughput test: firmware streams max-rate dummy frames (codec 0xFF) while on. */
    fun blast(on: Boolean) = byteArrayOf(BLAST.toByte(), if (on) 1 else 0)
    /** Ping carries a u32 LE token; the pong echoes it. */
    fun ping(token: Int) = byteArrayOf(PING.toByte(), token.toByte(), (token shr 8).toByte(), (token shr 16).toByte(), (token shr 24).toByte())
    fun pongToken(msg: ByteArray): Int? = if (msg.size >= 5 && msg[0].toInt() == PONG)
        (msg[1].toInt() and 0xff) or ((msg[2].toInt() and 0xff) shl 8) or ((msg[3].toInt() and 0xff) shl 16) or ((msg[4].toInt() and 0xff) shl 24) else null
}

class EspStats(val txFrames: Long, val rxFrames: Long, val rxLost: Long, val rxLate: Long, val scoDrops: Long) {
    companion object {
        fun parse(b: ByteArray): EspStats? {
            if (b.size < 21 || b[0].toInt() != Control.STATS) return null
            fun u32(o: Int) = (0 until 4).sumOf { (b[o + it].toLong() and 0xff) shl (8 * it) }
            return EspStats(u32(1), u32(5), u32(9), u32(13), u32(17))
        }
    }
}
