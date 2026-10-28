package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmEvaluatorTest {
    private fun reading(id: String, river: String, cls: FlowClass, level: Int? = 100) =
        StationReading(id, "Station $id", river, null, null, 10.0, level, null, cls, null)

    @Test
    fun riverRuleNotifiesOncePerExceedance() {
        val rules = listOf(AlarmRule(AlarmRule.Scope.RIVER, "Ill", minClass = FlowClass.HQ1))
        val readings = listOf(
            reading("1", "Ill", FlowClass.HQ5),
            reading("2", "Ill", FlowClass.NORMAL),
            reading("3", "Lutz", FlowClass.HQ10),
        )
        val (hits, active) = AlarmEvaluator.evaluate(rules, readings, emptySet())
        assertEquals(listOf("1"), hits.map { it.reading.hzbNr })
        assertEquals(setOf("1"), active)

        // Beim nächsten Lauf noch über der Schwelle: keine neue Meldung
        val (again, stillActive) = AlarmEvaluator.evaluate(rules, readings, active)
        assertTrue(again.isEmpty())
        assertEquals(setOf("1"), stillActive)

        // Wieder unter der Schwelle: zurückgesetzt, späterer Anstieg meldet erneut
        val calm = listOf(reading("1", "Ill", FlowClass.NORMAL))
        val (_, reset) = AlarmEvaluator.evaluate(rules, calm, stillActive)
        assertTrue(reset.isEmpty())
    }

    @Test
    fun stationRuleWithWaterLevel() {
        val rules = listOf(AlarmRule(AlarmRule.Scope.STATION, "200105", minLevelCm = 150))
        val (low, _) = AlarmEvaluator.evaluate(rules, listOf(reading("200105", "Lutz", FlowClass.NORMAL, 120)), emptySet())
        assertTrue(low.isEmpty())
        val (high, _) = AlarmEvaluator.evaluate(rules, listOf(reading("200105", "Lutz", FlowClass.NORMAL, 151)), emptySet())
        assertEquals(1, high.size)
        assertEquals("ab 150 cm", rules[0].describe())
    }
}
