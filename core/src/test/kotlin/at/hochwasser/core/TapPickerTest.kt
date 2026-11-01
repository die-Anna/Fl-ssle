package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TapPickerTest {
    private fun pick(vararg d: Float) = TapPicker.pick(d.indices.toList(), { d[it] }, radius = 40f)

    @Test
    fun nothingInRange() = assertEquals(emptyList(), pick(50f, 80f))

    @Test
    fun singleHitWellOutsideTheDot() = assertEquals(listOf(1), pick(90f, 35f))

    @Test
    fun clearlyNearestWins() = assertEquals(listOf(0), pick(8f, 30f))

    @Test
    fun similarDistancesAsk() = assertEquals(listOf(1, 0, 2), pick(20f, 15f, 30f, 60f))
}
