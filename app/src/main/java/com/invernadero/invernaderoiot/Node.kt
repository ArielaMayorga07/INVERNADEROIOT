package com.invernadero.invernaderoiot

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey
import kotlin.math.abs
import kotlin.random.Random

class GreenhouseNode(private val key: SecretKey) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    val state = MutableStateFlow(Snapshot())
    val events = MutableStateFlow<List<String>>(emptyList())
    private var server: ServerSocket? = null
    private var started = false

    private fun log(m: String) = events.update { (listOf(m) + it).take(12) }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val ss = ServerSocket(PORT).also { server = it }
                log("Nodo escuchando en el puerto $PORT")
                launch { simulate() }
                while (true) { val c = ss.accept(); launch { handle(c) } }
            } catch (e: Exception) { log("Servidor detenido: ${e.message}") }
        }
    }

    fun stop() { server?.close(); job.cancelChildren(); started = false }

    fun injectDry() { state.update { it.copy(soil = 20.0) }; log("Demo: suelo seco") }
    fun refill() { state.update { it.copy(tank = 100.0) }; log("Estanque rellenado") }

    private fun step(s: Snapshot): Snapshot {
        val dt = 2.0
        val temp = (s.temp + Random.nextDouble(-0.3, 0.3)).coerceIn(15.0, 40.0)
        var soil = s.soil - (0.25 + (temp - 20).coerceAtLeast(0.0) * 0.02)
        var tank = s.tank; var volume = s.volume; var total = s.total; var valve = s.valve
        if (s.auto && !valve && soil < SOIL_MIN && tank > 10) { valve = true; volume = 0.0 }
        var flow = 0.0
        if (valve) {
            val avail = tank / 100 * TANK_L
            val liters = minOf(FLOW_LPM * dt / 60, s.target - volume, avail).coerceAtLeast(0.0)
            volume += liters; total += liters
            tank -= liters / TANK_L * 100
            soil += liters * 6.0
            flow = liters / dt * 60
            if (volume >= s.target - 1e-3 || tank < 5 || soil >= 85) valve = false
        }
        return s.copy(ts = System.currentTimeMillis(), temp = temp, soil = soil.coerceIn(0.0, 100.0),
            tank = tank.coerceIn(0.0, 100.0), flow = flow, volume = volume, total = total, valve = valve)
    }

    private suspend fun simulate() {
        while (true) {
            delay(2000)
            val old = state.value
            val new = step(old)
            if (state.compareAndSet(old, new)) {
                if (!old.valve && new.valve) log("Válvula ABIERTA (objetivo ${"%.1f".format(new.target)} L)")
                if (old.valve && !new.valve) log("Válvula CERRADA: ${"%.1f".format(new.volume)} L entregados")
            }
        }
    }

    private suspend fun handle(sock: Socket) = coroutineScope {
        val peer = sock.inetAddress.hostAddress
        log("Conexión de $peer")
        val out = PrintWriter(sock.getOutputStream(), true)
        val sender = launch { state.collect { out.println(Crypto.seal(key, it.toJson().toString())) } }
        var fails = 0
        try {
            val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
            while (true) {
                val line = inp.readLine() ?: break
                val j = Crypto.open(key, line)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val fresh = j != null && abs(System.currentTimeMillis() - j.optLong("ts")) <= MAX_SKEW
                if (!fresh) {
                    if (++fails >= 3) { log("$peer bloqueado: mensajes no autenticados"); break }
                    continue
                }
                apply(j!!)
            }
        } catch (_: Exception) {
        } finally { sender.cancel(); sock.close(); log("Desconectado $peer") }
    }

    private fun apply(j: JSONObject) {
        val dev = j.optString("dev"); val on = j.optBoolean("on"); val v = j.optDouble("value", 0.0)
        state.update { s ->
            when (dev) {
                "riego" -> if (on) { if (s.tank < 5) s else s.copy(valve = true, volume = 0.0, auto = false) }
                else s.copy(valve = false, auto = false)
                "target" -> s.copy(target = v.coerceIn(0.5, 10.0))
                "auto" -> s.copy(auto = on)
                else -> s
            }
        }
        log(when (dev) { "target" -> "Objetivo = ${"%.1f".format(v)} L"; else -> "Comando: $dev = ${if (on) "ON" else "OFF"}" })
    }
}