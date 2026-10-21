package com.invernadero.invernaderoiot

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Db(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(db) } } }
    }
}

fun localIp(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp = cm.getLinkProperties(cm.activeNetwork)
    return lp?.linkAddresses?.map { it.address }
        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "sin conexión Wi-Fi"
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content
)

@Composable
fun App(db: Db) {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf<String?>(null) }
    var role by remember { mutableStateOf<String?>(null) }
    when {
        user == null -> LoginScreen(db) { user = it }
        role == null -> RoleScreen(user!!, { role = it }, { user = null })
        role == "node" -> NodeScreen(ctx) { role = null }
        else -> MonitorScreen(db) { role = null }
    }
}

@Composable
fun LoginScreen(db: Db, onOk: (String) -> Unit) {
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var fails by remember { mutableIntStateOf(0) }
    var lockUntil by remember { mutableLongStateOf(0L) }

    Page {
        Spacer(Modifier.height(40.dp))
        Text("🌱 Invernadero IoT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Inicia sesión para controlar la cantidad de agua del riego")
        OutlinedTextField(u, { u = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            p, { p = it }, label = { Text("Contraseña") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (System.currentTimeMillis() < lockUntil) { msg = "Demasiados intentos. Espera 30 s."; return@Button }
            if (db.login(u, p)) onOk(u.trim().lowercase())
            else {
                if (++fails >= 5) { lockUntil = System.currentTimeMillis() + 30_000; fails = 0 }
                msg = "Credenciales inválidas"
            }
        }) { Text("Ingresar") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            msg = when {
                u.trim().length < 3 -> "Usuario: mínimo 3 caracteres"
                p.length < 8 -> "Contraseña: mínimo 8 caracteres"
                db.register(u, p) -> "Usuario creado. Ya puedes ingresar."
                else -> "Ese usuario ya existe"
            }
        }) { Text("Registrarse") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun RoleScreen(user: String, onRole: (String) -> Unit, onLogout: () -> Unit) = Page {
    Text("Hola, $user 👋", style = MaterialTheme.typography.headlineSmall)
    Text("Instala esta app en dos dispositivos Android conectados a la misma red Wi-Fi y elige un rol en cada uno.")
    Button(modifier = Modifier.fillMaxWidth(), onClick = { onRole("node") }) { Text("📡 Dispositivo A: Nodo de riego (sensores y válvula)") }
    Button(modifier = Modifier.fillMaxWidth(), onClick = { onRole("monitor") }) { Text("📱 Dispositivo B: Monitor y control del riego") }
    OutlinedButton(onClick = onLogout) { Text("Cerrar sesión") }
}

@Composable
fun NodeScreen(ctx: Context, back: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    if (started) { NodeRunning(ctx, pin, back); return }
    Page {
        Text("Nodo de riego", style = MaterialTheme.typography.headlineSmall)
        Text("Define un PIN de enlace (mín. 6 caracteres). El monitor deberá usar el mismo PIN.")
        OutlinedTextField(pin, { pin = it }, label = { Text("PIN de enlace") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = pin.length >= 6, onClick = { started = true }) { Text("Iniciar nodo") }
        OutlinedButton(onClick = back) { Text("Volver") }
    }
}

@Composable
fun NodeRunning(ctx: Context, pin: String, back: () -> Unit) {
    val node = remember { GreenhouseNode(Crypto.channelKey(pin)).also { it.start() } }
    DisposableEffect(Unit) { onDispose { node.stop() } }
    val s by node.state.collectAsState()
    val ev by node.events.collectAsState()
    Page {
        Text("📡 Nodo de riego activo", style = MaterialTheme.typography.headlineSmall)
        Text("IP de este dispositivo: ${localIp(ctx)}   ·   Puerto: $PORT", fontWeight = FontWeight.Bold)
        Text("Ingresa esa IP en el dispositivo Monitor.")
        WaterReadings(s)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { node.injectDry() }) { Text("Simular suelo seco") }
            OutlinedButton(onClick = { node.refill() }) { Text("Rellenar estanque") }
        }
        Text("Eventos", fontWeight = FontWeight.Bold)
        ev.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = back) { Text("Detener nodo") }
    }
}

@Composable
fun MonitorScreen(db: Db, back: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<MonitorClient?>(null) }
    val c = client
    if (c != null) { MonitorLive(c, db) { c.disconnect(); client = null }; return }
    Page {
        Text("Monitor y control del riego", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(host, { host = it }, label = { Text("IP del nodo (ej. 192.168.1.15)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(pin, { pin = it }, label = { Text("PIN de enlace") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = host.isNotBlank() && pin.length >= 6, onClick = {
            client = MonitorClient(Crypto.channelKey(pin), db).also { it.connect(host.trim()) }
        }) { Text("Conectar") }
        OutlinedButton(onClick = back) { Text("Volver") }
    }
}

@Composable
fun MonitorLive(client: MonitorClient, db: Db, onClose: () -> Unit) {
    DisposableEffect(client) { onDispose { client.disconnect() } }
    val s by client.state.collectAsState()
    val st by client.status.collectAsState()
    var tgt by remember { mutableFloatStateOf(2f) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Page {
        Text("💧 Control de riego", style = MaterialTheme.typography.headlineSmall)
        Text("Estado: $st")
        s?.let { d ->
            WaterReadings(d)
            if (d.tank < 20) Alert("Estanque bajo: rellénalo")
            if (d.soil < 20) Alert("Suelo muy seco")
            if (d.soil > 80) Alert("Riesgo de sobrerriego")
            Text("Cantidad de agua por riego", fontWeight = FontWeight.Bold)
            Text("Objetivo: ${"%.1f".format(tgt)} L (la válvula se cierra sola al alcanzarlo)")
            Slider(value = tgt, onValueChange = { tgt = it }, valueRange = 0.5f..10f, steps = 18,
                onValueChangeFinished = { client.send("target", value = tgt.toDouble()) })
            ControlRow("Modo automático (riega si suelo < ${SOIL_MIN.toInt()} %)", d.auto) { client.send("auto", it) }
            ControlRow("Válvula de riego", d.valve) { client.send("riego", it) }
            Text("Historial de consumo (SQLite)", fontWeight = FontWeight.Bold)
            val hist = remember(d.ts) { db.lastReadings(8) }
            hist.forEach {
                Text("${fmt.format(Date(it.ts))}  Suelo ${"%.0f".format(it.soil)}% · Estanque ${"%.0f".format(it.tank)}% · " +
                    "Riego ${"%.1f".format(it.volume)} L · Total ${"%.1f".format(it.total)} L", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onClose) { Text("Desconectar") }
    }
}

@Composable
fun Alert(text: String) = Text("⚠ $text", color = Color.Red, fontWeight = FontWeight.Bold)

@Composable
fun WaterReadings(s: Snapshot) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueCard("Humedad suelo", "%.0f %%".format(s.soil), s.soil < 20 || s.soil > 80, Modifier.weight(1f))
        ValueCard("Estanque", "%.0f %% · %.1f L".format(s.tank, s.tank / 100 * TANK_L), s.tank < 20, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueCard("Agua entregada", "%.1f / %.1f L".format(s.volume, s.target), false, Modifier.weight(1f))
        ValueCard("Caudal", "%.1f L/min".format(s.flow), false, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueCard("Total acumulado", "%.1f L".format(s.total), false, Modifier.weight(1f))
        ValueCard("Temperatura", "%.1f °C".format(s.temp), false, Modifier.weight(1f))
    }
    LinearProgressIndicator(progress = { (s.volume / s.target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Text("Válvula: ${if (s.valve) "ABIERTA 💧" else "cerrada"}  ·  Modo: ${if (s.auto) "automático" else "manual"}", fontWeight = FontWeight.Bold)
}

@Composable
fun ValueCard(label: String, value: String, alert: Boolean, modifier: Modifier) = Card(
    modifier, colors = CardDefaults.cardColors(
        containerColor = if (alert) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.secondaryContainer)
) {
    Column(Modifier.padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ControlRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Text(label, Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
}