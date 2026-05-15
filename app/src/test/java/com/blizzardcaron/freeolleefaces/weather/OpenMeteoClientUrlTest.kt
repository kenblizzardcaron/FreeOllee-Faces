package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoClientUrlTest {

    @Test
    fun `buildUrl puts lat and lng into the query string`() {
        val url = OpenMeteoClient.buildUrl(44.31, -72.04, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should contain latitude param: $url", url.contains("latitude=44.31"))
        assertTrue("url should contain longitude param: $url", url.contains("longitude=-72.04"))
    }

    @Test
    fun `buildUrl emits temperature_unit=fahrenheit for FAHRENHEIT`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should request fahrenheit: $url", url.contains("temperature_unit=fahrenheit"))
    }

    @Test
    fun `buildUrl emits temperature_unit=celsius for CELSIUS`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.CELSIUS).toString()
        assertTrue("url should request celsius: $url", url.contains("temperature_unit=celsius"))
    }

    @Test
    fun `buildUrl always requests current temperature_2m`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should request current temperature_2m: $url", url.contains("current=temperature_2m"))
    }

    @Test
    fun `buildUrl uses HTTPS and the Open-Meteo host`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT)
        assertEquals("https", url.protocol)
        assertEquals("api.open-meteo.com", url.host)
    }
}
