package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
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

    /** Pure URL builder so the unit param is unit-testable without HTTP. */
    fun buildUrl(lat: Double, lng: Double, unit: TempUnit): URL =
        URL(
            "$BASE?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m&temperature_unit=${unit.openMeteoParam}"
        )

    /** Fetches `current.temperature_2m` in the requested [unit]. */
    suspend fun currentTemp(lat: Double, lng: Double, unit: TempUnit): Result<Double> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = buildUrl(lat, lng, unit).openConnection() as HttpURLConnection
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

    /**
     * v0.1 delegate kept temporarily so callers that hard-code Fahrenheit compile.
     * Task 5 removes this when MainActivity is rewritten to call [currentTemp] directly.
     */
    suspend fun currentTempF(lat: Double, lng: Double): Result<Double> =
        currentTemp(lat, lng, TempUnit.FAHRENHEIT)

    /** Extracts `current.temperature_2m` from the response JSON. Unit is whatever the URL requested. */
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
