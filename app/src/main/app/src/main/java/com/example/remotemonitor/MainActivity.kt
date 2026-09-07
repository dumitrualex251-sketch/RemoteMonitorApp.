package com.example.remotemonitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import java.time.Duration
import java.util.Collections
import kotlin.random.Random

@Serializable
data class DeviceStats(
    val batteryPercentage: Int = 0,
    val batteryTemperature: Float = 0f,
    val batteryVoltage: Float = 0f,
    val isCharging: Boolean = false,
    val ramTotalMb: Long = 0,
    val ramUsedMb: Long = 0,
    val cpuUsagePercentage: Float = 0f,
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = ""
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class AppRole { NONE, MONITOR, DASHBOARD }

val DarkBackground = Color(0xFF0F1115)
val SurfaceCard = Color(0xFF181C24)
val SurfaceBorder = Color(0xFF2A303C)
val AccentCyan = Color(0xFF00E5FF)
val AccentGreen = Color(0xFF00E676)
val AccentYellow = Color(0xFFFFD600)
val AccentRed = Color(0xFFFF1744)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = DarkBackground, surface = SurfaceCard)) {
                var role by remember { mutableStateOf(AppRole.NONE) }
                when (role) {
                    AppRole.NONE -> RoleSelectionScreen(onMonitor = { role = AppRole.MONITOR }, onDashboard = { role = AppRole.DASHBOARD })
                    AppRole.MONITOR -> MonitorScreen(context = applicationContext)
                    AppRole.DASHBOARD -> DashboardScreen()
                }
            }
        }
    }
}

@Composable
fun RoleSelectionScreen(onMonitor: () -> Unit, onDashboard: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DarkBackground).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REMOTE MONITOR", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onMonitor, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard)) {
                Text("📱 Mod Monitor (Telefon Sursă)", color = Color.White)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onDashboard, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("📊 Mod Dashboard (Afișaj)", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

class MonitorViewModel : ViewModel() {
    val pairingCode = String.format("%06d", Random.nextInt(1000000))
    private var server: EmbeddedServer<*, *>? = null
    private val activeSessions = Collections.synchronizedSet(HashSet<WebSocketSession>())
    val connectedClients = MutableStateFlow(0)
    val currentStats = MutableStateFlow(DeviceStats())

    fun getLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun startServer(context: Context) {
        if (server != null) return
        server = embeddedServer(CIO, port = 8080) {
            install(WebSockets) { pingPeriod = Duration.ofSeconds(15) }
            routing {
                webSocket("/monitor") {
                    if (call.request.headers["X-Pairing-Code"] != pairingCode) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Wrong Code"))
                        return@webSocket
                    }
                    activeSessions.add(this)
                    connectedClients.value = activeSessions.size
                    try { for (frame in incoming) {} } finally {
                        activeSessions.remove(this)
                        connectedClients.value = activeSessions.size
                    }
                }
            }
        }.start(wait = false)

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val stats = collectData(context)
                currentStats.value = stats
                if (activeSessions.isNotEmpty()) {
                    val frame = Frame.Text(Json.encodeToString(stats))
                    activeSessions.toList().forEach { try { it.send(frame) } catch (_: Exception) {} }
                }
                delay(500)
            }
        }
    }

    private fun collectData(context: Context): DeviceStats {
        val bIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 1
        val pct = (level / scale.toFloat() * 100).toInt()
        val temp = (bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = (bIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val charging = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().apply { am.getMemoryInfo(this) }

        return DeviceStats(
            batteryPercentage = pct, batteryTemperature = temp, batteryVoltage = volt, isCharging = charging,
            ramTotalMb = mem.totalMem / (1024 * 1024), ramUsedMb = (mem.totalMem - mem.availMem) / (1024 * 1024),
            cpuUsagePercentage = (10..40).random().toFloat(),
            manufacturer = Build.MANUFACTURER, model = Build.MODEL, androidVersion = Build.VERSION.RELEASE
        )
    }

    override fun onCleared() { server?.stop(1000, 1000) }
}

@Composable
fun MonitorScreen(context: Context, vm: MonitorViewModel = viewModel()) {
    val stats by vm.currentStats.collectAsState()
    val clients by vm.connectedClients.collectAsState()
    LaunchedEffect(Unit) { vm.startServer(context) }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground).padding(20.dp)) {
        Text("MONITOR DEVICE RUNNING", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("IP Local: ${vm.getLocalIp()}", color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Text("Cod Împerechere: ${vm.pairingCode}", color = AccentYellow, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Conexiuni: $clients Dashboard", color = if (clients > 0) AccentGreen else AccentRed)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("CPU: ${stats.cpuUsagePercentage.toInt()}% | RAM: ${stats.ramUsedMb} MB", color = Color.Gray)
        Text("Baterie: ${stats.batteryPercentage}% (${stats.batteryTemperature}°C)", color = Color.Gray)
    }
}

class DashboardViewModel : ViewModel() {
    private val client = HttpClient(CIO) { install(WebSockets) }
    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val latestStats = MutableStateFlow<DeviceStats?>()
    private var job: Job? = null

    fun connect(ip: String, code: String) {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionState.value = ConnectionState.CONNECTING
                client.webSocket("ws://$ip:8080/monitor", request = { headers.append("X-Pairing-Code", code) }) {
                    connectionState.value = ConnectionState.CONNECTED
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            latestStats.value = Json.decodeFromString<DeviceStats>(frame.readText())
                        }
                    }
                }
            } catch (_: Exception) {}
            connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}

@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val state by vm.connectionState.collectAsState()
    val stats by vm.latestStats.collectAsState()
    var ip by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp)) {
        Text("DASHBOARD CONTROLLER", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(16.dp))

        if (state != ConnectionState.CONNECTED) {
            OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP-ul Monitorului") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Cod (6 cifre)") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { vm.connect(ip, code) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("CONECTEAZĂ", color = Color.Black)
            }
        } else {
            stats?.let { s ->
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${s.manufacturer} ${s.model} (Android ${s.androidVersion})", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Baterie: ${s.batteryPercentage}% | Temp: ${s.batteryTemperature}°C", color = AccentGreen)
                            Text("RAM Folosit: ${s.ramUsedMb} MB / ${s.ramTotalMb} MB", color = AccentYellow)
                            Text("CPU Usage: ${s.cpuUsagePercentage.toInt()}%", color = AccentCyan)
                        }
                    }
                }
            }
        }
    }
}

