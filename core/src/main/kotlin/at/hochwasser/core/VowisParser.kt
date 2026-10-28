package at.hochwasser.core

import org.jsoup.Jsoup
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Liest die Stationstabelle von vowis.vorarlberg.at (Fluss, Flussgebiet).
 * Es gibt keine offizielle Schnittstelle, daher ist der Parser bewusst tolerant.
 */
object VowisParser {
    /** vowis gibt Zeiten in MEZ an (ganzjährig UTC+1). */
    private val MEZ = ZoneOffset.ofHours(1)
    private val TIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"),
    )
    private val HZB_IN_HREF = Regex("hzbnr=(\\d+)", RegexOption.IGNORE_CASE)
    private val GRAFIK_IN_HREF = Regex("Webgrafik=([^&\"')]+)", RegexOption.IGNORE_CASE)
    private val TRAILING_ID = Regex(",\\s*\\d{5,7}\\s*$")

    fun parseStationTable(html: String): List<StationReading> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<StationReading>()
        var basin: String? = null
        for (row in doc.select("tr")) {
            val cells = row.select("td, th")
            val rowText = row.text().trim()
            if (rowText.startsWith("Flussgebiet", ignoreCase = true)) {
                basin = rowText.substringAfter(':', rowText).trim().ifEmpty { null }
                continue
            }
            if (cells.size < 6) continue
            val link = cells[0].selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            val hzb = HZB_IN_HREF.find(href)?.groupValues?.get(1) ?: continue

            val stationText = cells[0].text().trim()
            val river = if (stationText.contains(" / ")) stationText.substringAfterLast(" / ").trim() else ""
            val name = link.text().trim().replace(TRAILING_ID, "").ifEmpty {
                stationText.substringBeforeLast(" / ").replace(TRAILING_ID, "").trim()
            }

            result += StationReading(
                hzbNr = hzb,
                name = name,
                river = river,
                basin = basin,
                measuredAt = parseTime(cells[1].text()),
                dischargeM3s = parseNumber(cells[2].text()),
                waterLevelCm = parseNumber(cells[3].text())?.toInt(),
                waterTempC = parseNumber(cells[4].text()),
                flowClass = FlowClass.parse(cells[5].text()),
                webGrafik = GRAFIK_IN_HREF.find(href)?.groupValues?.get(1)?.let { decode(it) },
            )
        }
        return result
    }

    internal fun parseNumber(text: String?): Double? {
        val t = text?.trim()?.replace(" ", "")?.replace(',', '.') ?: return null
        return Regex("-?\\d+(?:\\.\\d+)?").find(t)?.value?.toDoubleOrNull()
    }

    internal fun parseTime(text: String?): java.time.Instant? {
        val t = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        for (f in TIME_FORMATS) {
            runCatching { return LocalDateTime.parse(t, f).toInstant(MEZ) }
        }
        return null
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
