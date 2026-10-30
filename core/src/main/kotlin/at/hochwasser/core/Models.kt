package at.hochwasser.core

import java.time.Instant

/** Aktuelle Messwerte einer Station. */
data class StationReading(
    val hzbNr: String,
    val name: String,
    val river: String,
    val basin: String?,
    val measuredAt: Instant?,
    val dischargeM3s: Double?,
    val waterLevelCm: Int?,
    val waterTempC: Double?,
    val flowClass: FlowClass,
    /** Bildname, den vowis für die Detailseite der Station braucht. */
    val webGrafik: String? = null,
    /** Interne vowis-Kennung (z.B. V334648), nötig für den Verlauf. */
    val wisId: String? = null,
    /** Tendenz des Abflusses (positiv = steigend), sofern bekannt. */
    val trendQ: Double? = null,
    /** Tendenz des Wasserstands (positiv = steigend), sofern bekannt. */
    val trendW: Double? = null,
) {
    /**
     * Richtung wie auf der VOWIS-Karte: 1 steigend, -1 fallend, 0 gleichbleibend.
     * Vorrangig aus dem Pegeltrend (Schwelle ±0,2), sonst aus dem Abflusstrend.
     */
    val trend: Int?
        get() = when {
            trendW != null -> if (trendW > 0.2) 1 else if (trendW < -0.2) -1 else 0
            trendQ != null -> if (trendQ > 0.01) 1 else if (trendQ < -0.01) -1 else 0
            else -> null
        }
}

/** Daten, die sich selten ändern: Lage und Hochwasser-Kennwerte einer Station. */
data class StationDetails(
    val hzbNr: String,
    val lat: Double?,
    val lon: Double?,
    val meanDischargeM3s: Double?,
    /** Jährlichkeit (1, 5, 10, 30, ...) -> Abfluss in m³/s. */
    val hq: Map<Int, Double>,
)

data class Station(val reading: StationReading, val details: StationDetails?)

/** Ein Punkt einer Zeitreihe. */
data class TimeValue(val time: Instant, val value: Double)
