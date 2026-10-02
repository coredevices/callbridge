package callbridge

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

object CallbridgeGatt {
    val SERVICE = Uuid.parse("CB000001-5F6A-4E2B-9C1D-0C2A7B3E4D50")
    val AUDIO_TX = characteristicOf(SERVICE, Uuid.parse("CB000002-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    val AUDIO_RX = characteristicOf(SERVICE, Uuid.parse("CB000003-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    val CONTROL = characteristicOf(SERVICE, Uuid.parse("CB000004-5F6A-4E2B-9C1D-0C2A7B3E4D50"))
    const val MTU = 247
}

/** Advertisements from callbridge devices (filtered by service UUID). */
fun scanCallbridge(): Flow<Advertisement> = Scanner {
    filters { match { services = listOf(CallbridgeGatt.SERVICE) } }
}.advertisements

/** Platform MTU request; Android only, iOS negotiates on its own. */
expect suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int?

class BleTransport(advertisement: Advertisement) : Link {
    override val kind = "ble"
    private val peripheral = Peripheral(advertisement)
    val state: StateFlow<State> get() = peripheral.state

    /** Connects and negotiates MTU; returns the MTU (null where the platform does not report it). */
    suspend fun connect(): Int? {
        peripheral.connect()
        return peripheral.requestMtuOrNull(CallbridgeGatt.MTU)
    }

    suspend fun disconnect() = peripheral.disconnect()
    override suspend fun close() = disconnect()

    /** Raw audio frames from the ESP32. */
    override fun audioIn(): Flow<ByteArray> = peripheral.observe(CallbridgeGatt.AUDIO_TX)
    override fun controlIn(): Flow<ByteArray> = peripheral.observe(CallbridgeGatt.CONTROL)

    override suspend fun sendAudio(frame: ByteArray) = peripheral.write(CallbridgeGatt.AUDIO_RX, frame, WriteType.WithoutResponse)
    override suspend fun sendControl(msg: ByteArray) = peripheral.write(CallbridgeGatt.CONTROL, msg, WriteType.WithResponse)
}
