package com.invernadero.invernaderoiot

import org.json.JSONObject

const val PORT = 8888
const val MAX_SKEW = 5 * 60 * 1000L
const val TANK_L = 50.0
const val FLOW_LPM = 6.0
const val SOIL_MIN = 35.0

data class Snapshot(
    val ts: Long = System.currentTimeMillis(),
    val temp: Double = 24.0,
    val soil: Double = 50.0,
    val tank: Double = 80.0,
    val flow: Double = 0.0,
    val volume: Double = 0.0,
    val target: Double = 2.0,
    val total: Double = 0.0,
    val valve: Boolean = false,
    val auto: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", "state").put("ts", ts).put("temp", temp).put("soil", soil).put("tank", tank)
        .put("flow", flow).put("volume", volume).put("target", target).put("total", total)
        .put("valve", valve).put("auto", auto)

    companion object {
        fun fromJson(j: JSONObject) = Snapshot(
            j.getLong("ts"), j.getDouble("temp"), j.getDouble("soil"), j.getDouble("tank"),
            j.getDouble("flow"), j.getDouble("volume"), j.getDouble("target"), j.getDouble("total"),
            j.getBoolean("valve"), j.getBoolean("auto")
        )
    }
}