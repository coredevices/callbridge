package callbridge

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral

actual suspend fun Peripheral.requestMtuOrNull(mtu: Int): Int? =
    (this as? AndroidPeripheral)?.requestMtu(mtu)
