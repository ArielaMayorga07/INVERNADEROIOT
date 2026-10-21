package com.invernadero.invernaderoiot

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.SecretKey

class MonitorClient(private val key: SecretKey, private val db: Db) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow<Snapshot?>(null)
    val status = MutableStateFlow("Desconectado")
    @Volatile private var socket: Socket? = null
    @Volatile private var out: PrintWriter? = null

    fun connect(host: String) {
        scope.launch {
            status.value = "Conectando…"
            var myOut: PrintWriter? = null
            try {
                val s = Socket().also { it.connect(InetSocketAddress(host, PORT), 5000); socket = it }
                myOut = PrintWriter(s.getOutputStream(), true).also { out = it }
                status.value = "Conectado a $host"
                val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = inp.readLine() ?: break
                    val j = Crypto.open(key, line)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (j == null) { status.value = "PIN de enlace incorrecto o mensaje alterado"; break }
                    val snap = Snapshot.fromJson(j)
                    state.value = snap
                    db.insertReading(snap)
                }
                if (!status.value.startsWith("PIN")) status.value = "Conexión cerrada"
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                if (out === myOut) out = null
                socket?.close()
            }
        }
    }

    fun send(dev: String, on: Boolean = false, value: Double = 0.0) {
        scope.launch {
            val msg = JSONObject().put("t", "cmd").put("dev", dev).put("on", on).put("value", value)
                .put("ts", System.currentTimeMillis()).toString()
            out?.println(Crypto.seal(key, msg))
        }
    }

    fun disconnect() { socket?.close(); scope.coroutineContext.cancelChildren() }
}