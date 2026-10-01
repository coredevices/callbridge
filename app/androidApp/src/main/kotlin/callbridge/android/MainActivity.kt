package callbridge.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import callbridge.Control
import callbridge.Session
import callbridge.SessionState
import callbridge.scanCallbridge
import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<RelayService?>(null)
    private var denied by mutableStateOf(false)
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as RelayService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Notification permission is optional; Bluetooth is required for the connectedDevice foreground service.
            if (granted[Manifest.permission.BLUETOOTH_SCAN] == true && granted[Manifest.permission.BLUETOOTH_CONNECT] == true) startRelay()
            else denied = true
        }.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
        setContent {
            MaterialTheme {
                when {
                    denied -> Text("Bluetooth permission required. Grant it in Settings and relaunch.", Modifier.safeContentPadding())
                    else -> service?.let { Screen(it.session) } ?: Text("starting…", Modifier.safeContentPadding())
                }
            }
        }
    }

    private fun startRelay() {
        val i = Intent(this, RelayService::class.java)
        startForegroundService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() { if (service != null) unbindService(conn); super.onDestroy() }
}

@Composable
fun Screen(session: Session) {
    val state by session.state.collectAsState()
    val stats by session.stats.collectAsState()
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080/ws") }
    var room by remember { mutableStateOf("demo") }
    var echo by remember { mutableStateOf(true) }
    var loopback by remember { mutableStateOf(false) }

    Column(Modifier.safeContentPadding().padding(16.dp)) {
        Text("callbridge", style = MaterialTheme.typography.headlineMedium)
        Text("state: $state")
        Text("call: ${stats.callState}")
        Spacer(Modifier.height(12.dp))

        when (state) {
            is SessionState.Idle, is SessionState.Error -> Button(onClick = {
                scope.launch {
                    val adv: Advertisement = scanCallbridge().first()
                    session.connect(adv)
                }
            }) { Text("Scan + connect") }
            else -> Button(onClick = { scope.launch { session.disconnect() } }) { Text("Disconnect") }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("server ws url") }, modifier = Modifier.fillMaxWidth())
        Row { Text("echo mode"); Switch(echo, { echo = it }) }
        if (!echo) OutlinedTextField(room, { room = it }, label = { Text("room") })
        Button(enabled = state is SessionState.Connected, onClick = {
            scope.launch { session.startRelay(serverUrl, if (echo) "echo" else "room", room, 500) }
        }) { Text("Start relay") }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = { scope.launch { session.sendControl(Control.answer()) } }) { Text("Answer") }
            Spacer(Modifier.height(0.dp).padding(4.dp))
            Button(onClick = { scope.launch { session.sendControl(Control.hangup()) } }) { Text("Hang up") }
        }
        Row {
            Text("ESP loopback")
            Switch(loopback, { loopback = it; scope.launch { session.sendControl(Control.loopback(it)) } })
        }

        Spacer(Modifier.height(12.dp))
        Text("up   ESP→server: ${stats.espToServer} frames, lost ${stats.lostFromEsp}")
        LinearProgressIndicator(progress = { stats.levelUp / 32767f }, modifier = Modifier.fillMaxWidth())
        Text("down server→ESP: ${stats.serverToEsp} frames, lost ${stats.lostFromServer}")
        LinearProgressIndicator(progress = { stats.levelDown / 32767f }, modifier = Modifier.fillMaxWidth())
        stats.esp?.let { Text("esp tx ${it.txFrames} rx ${it.rxFrames} lost ${it.rxLost} late ${it.rxLate} sco_drops ${it.scoDrops}") }
        Button(onClick = { scope.launch { session.sendControl(Control.requestStats()) } }) { Text("Refresh ESP stats") }
    }
}
