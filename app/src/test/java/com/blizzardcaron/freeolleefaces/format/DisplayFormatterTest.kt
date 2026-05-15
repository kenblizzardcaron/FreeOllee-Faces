package com.blizzardcaron.freeolleefaces.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import com.blizzardcaron.freeolleefaces.format.TempUnit

class DisplayFormatterTest {

    @Test
    fun `temperature rounds and right-justifies with F suffix - typical`() {
        assertEquals("  72 F", DisplayFormatter.temperature(72.0))
        assertEquals("  72 F", DisplayFormatter.temperature(71.6)) // rounds up
        assertEquals("  72 F", DisplayFormatter.temperature(72.4)) // rounds down
        assertEquals("   0 F", DisplayFormatter.temperature(0.0))
    }

    @Test
    fun `temperature handles negative values`() {
        assertEquals(" -12 F", DisplayFormatter.temperature(-12.0))
    }

    @Test
    fun `temperature handles three-digit values`() {
        assertEquals(" 102 F", DisplayFormatter.temperature(102.0))
        assertEquals("-100 F", DisplayFormatter.temperature(-100.0))
    }

    @Test
    fun `temperature with explicit Celsius uses C suffix`() {
        assertEquals("  22 C", DisplayFormatter.temperature(22.0, TempUnit.CELSIUS))
        assertEquals(" -12 C", DisplayFormatter.temperature(-12.0, TempUnit.CELSIUS))
    }

    @Test
    fun `temperature with explicit Fahrenheit matches default overload`() {
        assertEquals(
            DisplayFormatter.temperature(72.0),
            DisplayFormatter.temperature(72.0, TempUnit.FAHRENHEIT)
        )
    }

    @Test
    fun `temperature default overload still produces F suffix`() {
        // Regression guard for v0.1 callers that pass just a Double.
        assertEquals("  72 F", DisplayFormatter.temperature(72.0))
    }

    @Test
    fun `sunTime sunrise single-digit hour shows am marker`() {
        // 6:29 AM sunrise -> "6:29ar"
        assertEquals("6:29ar", DisplayFormatter.sunTime(SunEventKind.SUNRISE, LocalTime.of(6, 29)))
    }

    @Test
    fun `sunTime sunset single-digit PM hour shows pm marker`() {
        // 8:15 PM sunset -> "8:15ps"
        assertEquals("8:15ps", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(20, 15)))
    }

    @Test
    fun `sunTime drops am-pm marker for two-digit hours`() {
        // 10:05 AM sunrise -> "10:05r"
        assertEquals("10:05r", DisplayFormatter.sunTime(SunEventKind.SUNRISE, LocalTime.of(10, 5)))
        // 12:30 PM sunset -> "12:30s"
        assertEquals("12:30s", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(12, 30)))
        // 11:25 PM sunset -> "11:25s"
        assertEquals("11:25s", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(23, 25)))
    }

    @Test
    fun `sunTime midnight is rendered as 12 AM`() {
        // 00:00 sunrise -> 12:00 AM -> "12:00r"
        assertEquals("12:00r", DisplayFormatter.sunTime(SunEventKind.SUNRISE, LocalTime.of(0, 0)))
    }

    @Test
    fun `sunTime noon is rendered as 12 PM`() {
        // 12:00 sunset -> "12:00s"
        assertEquals("12:00s", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(12, 0)))
    }

    @Test
    fun `custom pads shorter strings with spaces`() {
        assertEquals("hi    ", DisplayFormatter.custom("hi"))
        assertEquals("      ", DisplayFormatter.custom(""))
    }

    @Test
    fun `custom truncates longer strings`() {
        assertEquals("toolon", DisplayFormatter.custom("toolong"))
        assertEquals("123456", DisplayFormatter.custom("12345678"))
    }

    @Test
    fun `custom passes through exactly-6-char input`() {
        assertEquals("123456", DisplayFormatter.custom("123456"))
    }
}
