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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import callbridge.BleState
import callbridge.Control
import callbridge.ServerState
import callbridge.Session
import callbridge.scanCallbridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
            // Notification + mic are optional (mic only gates the SCO test); Bluetooth is required for the
            // connectedDevice foreground service.
            if (granted[Manifest.permission.BLUETOOTH_SCAN] == true && granted[Manifest.permission.BLUETOOTH_CONNECT] == true) startRelay()
            else denied = true
        }.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO))
        setContent {
            MaterialTheme {
                when {
                    denied -> Text("Bluetooth permission required. Grant it in Settings and relaunch.", Modifier.safeContentPadding())
                    else -> service?.let { Screen(it) } ?: Text("starting…", Modifier.safeContentPadding())
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
fun Screen(service: RelayService) {
    val session = service.session
    val sco = service.sco
    val ble by session.ble.collectAsState()
    val server by session.server.collectAsState()
    val stats by session.stats.collectAsState()
    val latency by session.latency.collectAsState()
    val scope = rememberCoroutineScope()
    var scoOpen by remember { mutableStateOf(false) }
    var scoBusy by remember { mutableStateOf(false) }
    var scoStatus by remember { mutableStateOf("") }
    var e2eStatus by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("ws://127.0.0.1:8080/ws") } // adb reverse tcp:8080 tcp:8765
    var room by remember { mutableStateOf("demo") }
    var echo by remember { mutableStateOf(true) }
    var loopback by remember { mutableStateOf(true) }   // firmware default
    var blast by remember { mutableStateOf(false) }
    fun ms(v: Double?) = v?.let { "%.0f ms".format(it) } ?: "pending"
    val bleBusy = ble is BleState.Connecting
    val serverBusy = server is ServerState.Connecting

    Column(Modifier.safeContentPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("callbridge", style = MaterialTheme.typography.headlineMedium)
        Text("ESP32 (BLE): $ble")
        Text("server: $server")
        Text("call: ${stats.callState}")
        Spacer(Modifier.height(12.dp))

        if (ble is BleState.Connected) {
            Button(onClick = { scope.launch { session.disconnectBle() } }) { Text("Disconnect ESP32") }
        } else {
            Button(enabled = !bleBusy, onClick = {
                scope.launch { runCatching { service.connectAny() }.onFailure { scoStatus = it.message ?: it.toString() } }
            }) { Text("Scan + connect ESP32") }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("server ws url") }, modifier = Modifier.fillMaxWidth())
        Row { Text("echo mode"); Switch(echo, { echo = it }) }
        if (!echo) OutlinedTextField(room, { room = it }, label = { Text("room") })
        var appEcho by remember { mutableStateOf(session.appEcho) }
        Row { Text("App echoes server audio back (test, bypasses dongle)"); Switch(appEcho, { appEcho = it; session.appEcho = it }) }
        if (server is ServerState.Connected) {
            Button(onClick = { scope.launch { session.disconnectServer() } }) { Text("Stop relay") }
        } else {
            Button(enabled = !serverBusy, onClick = {
                scope.launch { session.connectServer(serverUrl, if (echo) "echo" else "room", room, 0) }
            }) { Text("Start relay") }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(enabled = ble is BleState.Connected, onClick = { scope.launch { session.sendControl(Control.answer()) } }) { Text("Answer") }
            Spacer(Modifier.padding(4.dp))
            Button(enabled = ble is BleState.Connected, onClick = { scope.launch { session.sendControl(Control.hangup()) } }) { Text("Hang up") }
        }
        Row {
            Text("ESP loopback")
            Switch(loopback, { loopback = it; session.loopbackWanted = it; scope.launch { session.sendControl(Control.loopback(it)) } }, enabled = ble is BleState.Connected)
            Spacer(Modifier.padding(8.dp))
            Text("BLE blast")
            Switch(blast, { blast = it; scope.launch { session.sendControl(Control.blast(it)) } }, enabled = ble is BleState.Connected)
        }
        Text("BLE audio_tx: ${stats.bleFramesPerSec} frames/s, %.1f kbps".format(stats.bleKbps))

        Spacer(Modifier.height(12.dp))
        Text("SCO test (no call needed; ESP loopback must be on)", style = MaterialTheme.typography.titleSmall)
        Row {
            Text("SCO open")
            Switch(scoOpen, enabled = !scoBusy, onCheckedChange = { on ->
                scope.launch {
                    scoBusy = true
                    try {
                        if (on) { sco.open(); scoOpen = true; scoStatus = "SCO up" }
                        else { sco.close(); scoOpen = false; scoStatus = "" }
                    } catch (e: Exception) { scoOpen = false; scoStatus = e.message ?: e.toString() }
                    scoBusy = false
                }
            })
        }
        var phoneEcho by remember { mutableStateOf(false) }
        Row {
            Text("Phone echoes SCO back (far end hears itself; turn ESP loopback off)")
            Switch(phoneEcho, enabled = scoOpen && !scoBusy, onCheckedChange = { on ->
                phoneEcho = on
                if (on) scope.launch { sco.startEcho() } else sco.stopEcho()
            })
        }
        Button(enabled = scoOpen && !scoBusy && !phoneEcho, onClick = {
            scope.launch {
                scoBusy = true
                scoStatus = "measuring…"
                val ms = runCatching { sco.measure() }.getOrElse { scoStatus = it.message ?: it.toString(); null }
                session.setScoRoundTrip(ms)
                if (ms != null) scoStatus = "SCO round trip %.0f ms".format(ms)
                else if (scoStatus == "measuring…") scoStatus = "burst never came back (loopback off / too quiet?)"
                scoBusy = false
            }
        }) { Text("Measure SCO round trip") }
        Text(scoStatus)

        Spacer(Modifier.height(12.dp))
        Text("End-to-end test (real audio)", style = MaterialTheme.typography.titleSmall)
        Text("Needs ESP32 + server + SCO up. Plays a tone into the call path; the server detects it on the way up, the phone hears it come back.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = scoOpen && !scoBusy && ble is BleState.Connected && server is ServerState.Connected, onClick = {
            scope.launch {
                android.util.Log.i("callbridge", "e2e button clicked")
                scoBusy = true; e2eStatus = "running…"
                val r = runCatching { session.runE2E { onFired -> sco.fireAndListen(onFired = onFired) } }
                    .getOrElse { callbridge.E2EResult(null, null, null, it.message ?: it.toString()) }
                e2eStatus = "up %s · down %s · full loop %s · %s".format(ms(r.upMs), ms(r.downMs), ms(r.fullRtMs), r.message)
                scoBusy = false
            }
        }) { Text("Run end-to-end test") }
        Text(e2eStatus)

        Spacer(Modifier.height(12.dp))
        Text("Latency (round trip)", style = MaterialTheme.typography.titleSmall)
        Text("SCO phone⇄ESP ${ms(latency.scoRtMs)} · BLE ESP⇄app ${ms(latency.bleRtMs)} · WS app⇄server ${ms(latency.wsRtMs)}")
        Text("total ${ms(latency.totalRtMs)}, est. one-way ${ms(latency.totalRtMs?.div(2))}")
        Text("measured: up ${ms(latency.upMs)} · down ${ms(latency.downMs)} · full loop ${ms(latency.fullRtMs)}")

        Spacer(Modifier.height(12.dp))
        Text("up   ESP→server: ${stats.espToServer} frames, lost ${stats.lostFromEsp}")
        LinearProgressIndicator(progress = { stats.levelUp / 32767f }, modifier = Modifier.fillMaxWidth())
        Text("down server→ESP: ${stats.serverToEsp} frames, lost ${stats.lostFromServer}")
        LinearProgressIndicator(progress = { stats.levelDown / 32767f }, modifier = Modifier.fillMaxWidth())
        stats.esp?.let { Text("esp tx ${it.txFrames} rx ${it.rxFrames} lost ${it.rxLost} late ${it.rxLate} sco_drops ${it.scoDrops}") }
        Button(enabled = ble is BleState.Connected, onClick = { scope.launch { session.sendControl(Control.requestStats()) } }) { Text("Refresh ESP stats") }
    }
}
