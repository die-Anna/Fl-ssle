package at.hochwasser.core

import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant

/** Lädt Daten von vowis.vorarlberg.at. Blockierend, also im Hintergrund aufrufen. */
class VowisClient(private val userAgent: String = "Fluessle (privat)") {

    /**
     * Stationen mit Lage, aktuellen Werten und HQ-Kennwerten aus dem WFS-Dienst.
     * Fluss und Flussgebiet kommen aus der Stationstabelle; fällt sie aus, bleibt die Karte trotzdem nutzbar.
     */
    fun fetchStations(): List<Station> {
        val stations = VowisWfs.parse(getText(WFS_URL))
        val table = runCatching { VowisParser.parseStationTable(getText(TABLE_URL)) }
            .getOrDefault(emptyList())
            .associateBy { it.hzbNr }
        return stations.map { s ->
            val t = table[s.reading.hzbNr] ?: return@map s
            s.copy(
                reading = s.reading.copy(
                    river = s.reading.river.ifEmpty { t.river },
                    basin = t.basin,
                    waterTempC = s.reading.waterTempC ?: t.waterTempC,
                    webGrafik = t.webGrafik,
                ),
            )
        }
    }

    /** Abfluss-Verlauf (m³/s). Der Server liefert ab etwa 1 h vor [from], daher wird nachgefiltert. */
    fun fetchDischargeHistory(wisId: String, from: Instant, to: Instant): List<TimeValue> {
        val url = "$CHARTS/dlt_DownloadAbfluss.php?start=${from.toEpochMilli()}&end=${to.toEpochMilli()}&id0=$wisId"
        val bytes = Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(60_000)
            .maxBodySize(0)
            .ignoreContentType(true)
            .execute()
            .bodyAsBytes()
        val values = VowisHistory.parseCsv(String(bytes, Charset.forName("ISO-8859-1")))
        val filtered = VowisHistory.filterRange(values, from, to)
        val span = Duration.between(from, to)
        return when {
            span > Duration.ofDays(60) -> VowisHistory.downsample(filtered, 6 * 3600)
            span > Duration.ofDays(4) -> VowisHistory.downsample(filtered, 3600)
            else -> filtered
        }
    }

    private fun getText(url: String): String =
        Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(20_000)
            .maxBodySize(0)
            .ignoreContentType(true)
            .execute()
            .body()

    companion object {
        const val BASE = "https://vowis.vorarlberg.at/stationsInfo/"
        const val TABLE_URL = BASE + "tbl_Abflussstationen.aspx"
        const val CHARTS = "https://vowis.vorarlberg.at/charts"
        const val WFS_URL = "https://vowis.vorarlberg.at/geoserver/owf/ows?service=WFS&request=GetFeature" +
            "&typename=owf:Pegel&srsname=EPSG:4326&outputFormat=application/json"

        /** Wasserstand-Verlauf gibt es nur als Grafik (der CSV-Export dafür ist leer). */
        fun waterLevelChartUrl(wisId: String, from: Instant, to: Instant): String =
            "$CHARTS/dlt_wasserstand.php?start=${from.toEpochMilli()}&end=${to.toEpochMilli()}" +
                "&id0=$wisId&id1=0&id2=0&id3=0&id4=0"
    }
}
