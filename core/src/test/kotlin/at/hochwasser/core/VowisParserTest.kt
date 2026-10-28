package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VowisParserTest {
    // Nachgebaut nach der Struktur der echten Tabelle (Stand 26.10.2026)
    private val table = """
        <table>
          <tr><th><b>Station / Gewässer</b></th><th>Messzeitpunkt [MEZ]</th><th>Abfluss (m³/s)</th>
              <th>Wasserst. (cm)</th><th>WTemp. (°C)</th><th>Jährlich- keit</th></tr>
          <tr><td><b>Flussgebiet: Rhein</b></td><td></td><td></td><td></td><td></td><td></td></tr>
          <tr><td><a href="javascript:oeffnefenster(&quot;./_Abfluss/ofwStation.aspx?hzbnr=200014&amp;LW_Zeitabfluss=0&amp;LW_Wasserstand=0&amp;Abfluss=0&amp;Webgrafik=Bangs&amp;Status=yes&quot;)">Bangs, 200014</a> / Rhein</td>
              <td>25.10.26 18:50</td><td>40,9</td><td>583</td><td></td><td>≤ MJNQ</td></tr>
          <tr><td><b>Flussgebiet: Ill</b></td><td></td><td></td><td></td><td></td><td></td></tr>
          <tr><td><a href="javascript:oeffnefenster(&quot;./_Abfluss/ofwStation.aspx?hzbnr=200105&amp;Webgrafik=Garsella&amp;Status=yes&quot;)">Garsella, 200105</a> / Lutz</td>
              <td>25.10.26 18:45</td><td>25,9</td><td>104</td><td>7,4</td><td>MQ-HQ1</td></tr>
        </table>
    """.trimIndent()

    @Test
    fun parsesStationTable() {
        val rows = VowisParser.parseStationTable(table)
        assertEquals(2, rows.size)
        val bangs = rows[0]
        assertEquals("200014", bangs.hzbNr)
        assertEquals("Bangs", bangs.name)
        assertEquals("Rhein", bangs.river)
        assertEquals("Rhein", bangs.basin)
        assertEquals(40.9, bangs.dischargeM3s)
        assertEquals(583, bangs.waterLevelCm)
        assertEquals(null, bangs.waterTempC)
        assertEquals(FlowClass.LOW, bangs.flowClass)
        assertEquals("Bangs", bangs.webGrafik)
        assertEquals("2026-10-25T17:50:00Z", bangs.measuredAt.toString())

        val garsella = rows[1]
        assertEquals("Ill", garsella.basin)
        assertEquals("Lutz", garsella.river)
        assertEquals(7.4, garsella.waterTempC)
        assertEquals(FlowClass.NORMAL, garsella.flowClass)
    }
}
