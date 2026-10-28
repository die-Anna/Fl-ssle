package at.hochwasser.core

/**
 * Abflussklasse wie in der Spalte "Jährlichkeit" auf vowis.
 * Reihenfolge = Schweregrad, daher sind die Klassen vergleichbar.
 */
enum class FlowClass(val label: String) {
    UNKNOWN("unbekannt"),
    LOW("≤ MJNQ"),
    BELOW_MEAN("MJNQ–MQ"),
    NORMAL("MQ–HQ1"),
    HQ1("HQ1–HQ5"),
    HQ5("HQ5–HQ10"),
    HQ10("HQ10–HQ30"),
    HQ30("HQ30–HQ100"),
    HQ100("HQ100–HQ300"),
    HQ300("> HQ300");

    val isFlood: Boolean get() = this >= HQ1

    companion object {
        /** Liest Texte wie "≤ MJNQ", "MQ-HQ1", "HQ10 - HQ30" oder "> HQ300". */
        fun parse(text: String?): FlowClass {
            if (text.isNullOrBlank()) return UNKNOWN
            val t = text.uppercase().replace(Regex("\\s+"), "").replace('–', '-')
            if (t.startsWith(">")) {
                return fromLowerBound(t.removePrefix(">"))
            }
            if (t.startsWith("≤") || t.startsWith("<=") || t.startsWith("<")) {
                return if (t.contains("MJNQ")) LOW else UNKNOWN
            }
            val lower = t.substringBefore('-')
            return fromLowerBound(lower)
        }

        private fun fromLowerBound(bound: String): FlowClass = when (bound) {
            "MJNQ" -> BELOW_MEAN
            "MQ" -> NORMAL
            "HQ1" -> HQ1
            "HQ5" -> HQ5
            "HQ10" -> HQ10
            "HQ30" -> HQ30
            "HQ100" -> HQ100
            "HQ300" -> HQ300
            else -> UNKNOWN
        }

        /** Klasse aus Abfluss und HQ-Werten, falls die Tabelle keine liefert. */
        fun fromDischarge(q: Double?, details: StationDetails?): FlowClass {
            if (q == null || details == null) return UNKNOWN
            val hq = details.hq
            return when {
                hq[300]?.let { q > it } == true -> HQ300
                hq[100]?.let { q >= it } == true -> HQ100
                hq[30]?.let { q >= it } == true -> HQ30
                hq[10]?.let { q >= it } == true -> HQ10
                hq[5]?.let { q >= it } == true -> HQ5
                hq[1]?.let { q >= it } == true -> HQ1
                details.meanDischargeM3s?.let { q >= it } == true -> NORMAL
                details.meanDischargeM3s != null -> BELOW_MEAN
                else -> UNKNOWN
            }
        }
    }
}
