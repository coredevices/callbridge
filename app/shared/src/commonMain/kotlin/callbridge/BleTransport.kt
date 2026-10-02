package callbridge

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

object CallbridgeGatt {
    val SERVICE = Uuid.parse("CB000001-5F6A-4E2B-9C1D-0C2A7B3E4D50")
    val AUDIO_TX = characteristicOf(SERVICE, Uuid.parse("CB000002-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    val AUDIO_RX = characteristicOf(SERVICE, Uuid.parse("CB000003-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    val CONTROL = characteristicOf(SERVICE, Uuid.parse("CB000004-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    const val MTU = 247
}

private const val PACKET_MAX = 244

/** Advertisements from callbridge devices (filtered by service UUID). */
fun scanCallbridge(): Flow<Advertisement> = Scanner {
    filters { match { services = listOf(CallbridgeGatt.SERVICE) } }
}.advertisements

/** Platform MTU request; Android only, iOS negotiates on its own. */
expect suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int?

/** BLE peripheral for a known address (no scan); null where the platform needs a scan first (iOS). */
expect fun peripheralForAddress(address: String): Peripheral?

class BleTransport(private val peripheral: Peripheral) : Link {
    constructor(advertisement: Advertisement) : this(Peripheral(advertisement))
    override val kind = "ble"
    val state: StateFlow<State> get() = peripheral.state

    /** Connects and negotiates MTU; returns the MTU (null where the platform does not report it). */
    suspend fun connect(): Int? {
        peripheral.connect()
        return peripheral.requestMtuOrNull(CallbridgeGatt.MTU)
    }

    suspend fun disconnect() = peripheral.disconnect()
    override suspend fun close() = disconnect()

    /** Audio frames from the ESP32: each notification holds one or more [len u8][frame] records. */
    override fun audioIn(): Flow<ByteArray> = peripheral.observe(CallbridgeGatt.AUDIO_TX).transform { pkt ->
        var i = 0
        while (i < pkt.size) {
            val n = pkt[i].toInt() and 0xff
            if (n == 0 || i + 1 + n > pkt.size) break
            emit(pkt.copyOfRange(i + 1, i + 1 + n))
            i += 1 + n
        }
    }
    override fun controlIn(): Flow<ByteArray> = peripheral.observe(CallbridgeGatt.CONTROL)

    override suspend fun sendAudio(frame: ByteArray) = sendAudioBatch(listOf(frame))

    /** Packs frames as [len u8][frame] records into as few writes as fit in one packet (MTU 247 -> 244 bytes). */
    override suspend fun sendAudioBatch(frames: List<ByteArray>) {
        var buf = ByteArray(0)
        for (f in frames) {
            if (buf.isNotEmpty() && buf.size + 1 + f.size > PACKET_MAX) {
                peripheral.write(CallbridgeGatt.AUDIO_RX, buf, WriteType.WithoutResponse); buf = ByteArray(0)
            }
            buf += byteArrayOf(f.size.toByte()) + f
        }
        if (buf.isNotEmpty()) peripheral.write(CallbridgeGatt.AUDIO_RX, buf, WriteType.WithoutResponse)
    }
    override suspend fun sendControl(msg: ByteArray) = peripheral.write(CallbridgeGatt.CONTROL, msg, WriteType.WithResponse)
}
