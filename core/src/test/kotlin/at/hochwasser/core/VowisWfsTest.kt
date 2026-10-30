package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VowisWfsTest {
    // Echte Antwort für Garsella (26.10.2026), gekürzt
    private val json = """
        {"type":"FeatureCollection","features":[{"type":"Feature","id":"Pegel.1",
         "geometry":{"type":"Point","coordinates":[9.8759,47.2267]},
         "properties":{"HZBNR":"200105","WISID":"V334648","STATIONSNAME":"Garsella, 200105",
          "MJNQ":"1.04","MQ":"5.73","HQ1":"45.0","HQ5":"70.0","HQ10":"80.0","HQ30":"100.0",
          "ZP_W":"2026-10-26T16:00:00Z","W":"67.9","ZP_Q":"2026-10-26T16:05:00Z","Q":" 5.46",
          "HQKLASSE":"MJNQ-MQ","TRENDW":-0.237,"TRENDQ":-0.086}}]}
    """.trimIndent()

    @Test
    fun parsesFeature() {
        val s = VowisWfs.parse(json).single()
        assertEquals("200105", s.reading.hzbNr)
        assertEquals("Garsella", s.reading.name)
        assertEquals("V334648", s.reading.wisId)
        assertEquals(5.46, s.reading.dischargeM3s)
        assertEquals(68, s.reading.waterLevelCm)
        assertEquals(FlowClass.BELOW_MEAN, s.reading.flowClass)
        assertEquals(-0.086, s.reading.trendQ)
        assertEquals(-0.237, s.reading.trendW)
        assertEquals(-1, s.reading.trend)
        assertEquals("2026-10-26T16:05:00Z", s.reading.measuredAt.toString())
        assertEquals(47.2267, s.details!!.lat)
        assertEquals(9.8759, s.details!!.lon)
        assertEquals(5.73, s.details!!.meanDischargeM3s)
        assertEquals(mapOf(1 to 45.0, 5 to 70.0, 10 to 80.0, 30 to 100.0), s.details!!.hq)
    }
}
