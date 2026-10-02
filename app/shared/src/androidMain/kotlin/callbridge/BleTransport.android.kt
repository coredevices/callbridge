package callbridge

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral


actual suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int? {
    val p = this as? AndroidPeripheral ?: return null
    // 7.5-15 ms connection interval; the audio stream needs one notification every 20 ms
    p.requestConnectionPriority(AndroidPeripheral.Priority.High)
    return p.requestMtu(mtu)
}
