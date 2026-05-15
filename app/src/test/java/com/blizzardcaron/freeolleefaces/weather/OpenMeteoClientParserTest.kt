package com.blizzardcaron.freeolleefaces.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoClientParserTest {

    @Test
    fun `parses temperature_2m from a current response`() {
        val json = """
            {
              "current": {
                "time": "2026-05-14T15:00",
                "interval": 900,
                "temperature_2m": 71.6
              },
              "current_units": { "temperature_2m": "°F" }
            }
        """.trimIndent()

        assertEquals(71.6, OpenMeteoClient.parseCurrentTemperatureF(json), 1e-6)
    }

    @Test
    fun `parses negative temperatures`() {
        val json = """{"current":{"temperature_2m":-15.4}}"""
        assertEquals(-15.4, OpenMeteoClient.parseCurrentTemperatureF(json), 1e-6)
    }

    @Test
    fun `throws when current block is missing`() {
        val json = """{"hourly":{"temperature_2m":[60]}}"""
        try {
            OpenMeteoClient.parseCurrentTemperatureF(json)
            error("expected to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("current") == true)
        }
    }

    @Test
    fun `throws when temperature_2m is missing`() {
        val json = """{"current":{"time":"2026-05-14T15:00"}}"""
        try {
            OpenMeteoClient.parseCurrentTemperatureF(json)
            error("expected to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("temperature_2m") == true)
        }
    }
}
