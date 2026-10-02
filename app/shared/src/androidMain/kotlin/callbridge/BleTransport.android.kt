package callbridge

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral


actual suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int? {
    val p = this as? AndroidPeripheral ?: return null
    // no requestConnectionPriority: the dongle requests a specific interval (see firmware ble_gatt.c),
    // and Android's HIGH preset (11.25-15 ms, latency 1) would compete with it
    return p.requestMtu(mtu)
}

actual fun peripheralForAddress(address: String): Peripheral? = Peripheral(address)
