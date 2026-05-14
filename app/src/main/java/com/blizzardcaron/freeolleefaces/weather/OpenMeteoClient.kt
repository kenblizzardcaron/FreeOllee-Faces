package com.blizzardcaron.freeolleefaces.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    suspend fun currentTempF(lat: Double, lng: Double): Result<Double> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    "$BASE?latitude=$lat&longitude=$lng" +
                        "&current=temperature_2m&temperature_unit=fahrenheit"
                )
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        error("HTTP $code from Open-Meteo: $errBody")
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseCurrentTemperatureF(body)
                } finally {
                    conn.disconnect()
                }
            }
        }

    fun parseCurrentTemperatureF(json: String): Double {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalStateException("response is not valid JSON: ${e.message}", e)
        }
        val current = root.optJSONObject("current")
            ?: throw IllegalStateException("response missing 'current' block")
        if (!current.has("temperature_2m")) {
            throw IllegalStateException("response 'current' missing 'temperature_2m'")
        }
        return current.getDouble("temperature_2m")
    }
}
