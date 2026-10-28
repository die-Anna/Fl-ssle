package at.hochwasser.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Liest den GeoServer-Dienst, den die vowis-Karte selbst nutzt (Layer owf:Pegel).
 * Liefert Lage (WGS84), aktuelle Werte, HQ-Kennwerte und die WISID je Station.
 */
object VowisWfs {
    private val json = Json { ignoreUnknownKeys = true }
    private val HQ_KEYS = listOf(1, 5, 10, 30, 100, 300)

    fun parse(text: String): List<Station> {
        val root = json.parseToJsonElement(text).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { f -> parseFeature(f.jsonObject) }
    }

    private fun parseFeature(f: JsonObject): Station? {
        val p = f["properties"]?.jsonObject ?: return null
        val hzb = p.str("HZBNR") ?: return null
        val coords = (f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
        val lon = (coords?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        val lat = (coords?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull

        val name = (p.str("STATIONSNAME") ?: hzb).replace(Regex(",\\s*\\d{5,7}\\s*$"), "").trim()
        val q = p.num("Q")
        val details = StationDetails(
            hzbNr = hzb,
            lat = lat,
            lon = lon,
            meanDischargeM3s = p.num("MQ"),
            hq = HQ_KEYS.mapNotNull { k -> p.num("HQ$k")?.let { k to it } }.toMap(),
        )
        val reading = StationReading(
            hzbNr = hzb,
            name = name,
            river = p.str("GEWAESSER") ?: "",
            basin = null,
            measuredAt = p.str("ZP_Q")?.let { runCatching { Instant.parse(it) }.getOrNull() },
            dischargeM3s = q,
            waterLevelCm = p.num("W")?.let { Math.round(it).toInt() },
            waterTempC = p.num("WT"),
            flowClass = FlowClass.parse(p.str("HQKLASSE")).takeUnless { it == FlowClass.UNKNOWN }
                ?: FlowClass.fromDischarge(q, details),
            wisId = p.str("WISID"),
            trendQ = p.num("TRENDQ"),
        )
        return Station(reading, details)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** Zahlen kommen teils als Text (" 5.46"), teils als Zahl. */
    private fun JsonObject.num(key: String): Double? =
        str(key)?.replace(',', '.')?.toDoubleOrNull()
}
