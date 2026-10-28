package at.hochwasser.core

/**
 * Ein Alarm gilt entweder für einen einzelnen Pegel (hzbNr) oder für alle Pegel eines Flusses.
 * Ausgelöst wird ab einer Abflussklasse (z.B. HQ1) oder ab einem eigenen Wasserstand in cm.
 */
data class AlarmRule(
    val scope: Scope,
    /** hzbNr bei STATION, Flussname bei RIVER */
    val key: String,
    val minClass: FlowClass? = null,
    val minLevelCm: Int? = null,
) {
    enum class Scope { STATION, RIVER }

    fun appliesTo(r: StationReading): Boolean = when (scope) {
        Scope.STATION -> r.hzbNr == key
        Scope.RIVER -> r.river.equals(key, ignoreCase = true)
    }

    fun isTriggeredBy(r: StationReading, flowClass: FlowClass): Boolean {
        val byClass = minClass != null && flowClass != FlowClass.UNKNOWN && flowClass >= minClass
        val byLevel = minLevelCm != null && r.waterLevelCm != null && r.waterLevelCm >= minLevelCm
        return byClass || byLevel
    }

    fun describe(): String = buildList {
        minClass?.let { add("ab ${it.label.substringBefore('–')}") }
        minLevelCm?.let { add("ab $it cm") }
    }.joinToString(" oder ")
}

data class AlarmHit(val reading: StationReading, val flowClass: FlowClass, val rule: AlarmRule)

object AlarmEvaluator {
    /**
     * @param alreadyAlerted Pegel, für die schon benachrichtigt wurde und die noch über der Schwelle sind
     * @return neue Treffer (nur einmal pro Überschreitung) und die aktualisierte Menge
     */
    fun evaluate(
        rules: List<AlarmRule>,
        readings: List<StationReading>,
        alreadyAlerted: Set<String>,
        classOf: (StationReading) -> FlowClass = { it.flowClass },
    ): Pair<List<AlarmHit>, Set<String>> {
        val hits = mutableListOf<AlarmHit>()
        val active = mutableSetOf<String>()
        for (r in readings) {
            val cls = classOf(r)
            val rule = rules.firstOrNull { it.appliesTo(r) && it.isTriggeredBy(r, cls) } ?: continue
            active += r.hzbNr
            if (r.hzbNr !in alreadyAlerted) hits += AlarmHit(r, cls, rule)
        }
        return hits to active
    }
}
