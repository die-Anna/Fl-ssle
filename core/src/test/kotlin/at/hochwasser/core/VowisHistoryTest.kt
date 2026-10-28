package at.hochwasser.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VowisHistoryTest {
    // Aufbau wie dlt_DownloadAbfluss.php (Latin-1 bereits dekodiert)
    private val csv = """
        Abflussdaten für Messstelle Garsella, 200105;;; Wassertemperaturdaten für Messstelle Garsella, 200105;;;
        Zeit [MEZ];Abfluss (m³/s);;ZEIT[MEZ];Temperatur (°C);;
        23-10-2026 23:05:00;1,8457608222961426;;;;;
        23-10-2026 23:10:00;1,8537046909332275;;;;;
        24-10-2026 00:05:00;2,5;;;;;
        ;;;;;;
        Achtung Rohdaten! Mess- und Übertragungsfehler können nicht ausgeschlossen werden.;;;
    """.trimIndent()

    @Test
    fun parsesCsv() {
        val v = VowisHistory.parseCsv(csv)
        assertEquals(3, v.size)
        assertEquals("2026-10-23T22:05:00Z", v[0].time.toString())
        assertEquals(1.8457608222961426, v[0].value)
    }

    @Test
    fun downsampleKeepsPeak() {
        val v = VowisHistory.parseCsv(csv)
        val hourly = VowisHistory.downsample(v, 3600)
        assertEquals(2, hourly.size)
        assertEquals(1.8537046909332275, hourly[0].value)
    }

    @Test
    fun filtersRange() {
        val v = VowisHistory.parseCsv(csv)
        val f = VowisHistory.filterRange(v, Instant.parse("2026-10-23T23:00:00Z"), Instant.parse("2026-10-24T00:00:00Z"))
        assertEquals(1, f.size)
    }
}
