package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowClassTest {
    @Test
    fun parsesLabels() {
        assertEquals(FlowClass.LOW, FlowClass.parse("≤ MJNQ"))
        assertEquals(FlowClass.BELOW_MEAN, FlowClass.parse("MJNQ-MQ"))
        assertEquals(FlowClass.NORMAL, FlowClass.parse("MQ-HQ1"))
        assertEquals(FlowClass.HQ1, FlowClass.parse("HQ1 - HQ5"))
        assertEquals(FlowClass.HQ10, FlowClass.parse("HQ10–HQ30"))
        assertEquals(FlowClass.HQ300, FlowClass.parse("> HQ300"))
        assertEquals(FlowClass.UNKNOWN, FlowClass.parse(""))
    }

    @Test
    fun classFromDischarge() {
        val d = StationDetails("1", null, null, 5.73, mapOf(1 to 45.0, 5 to 70.0, 10 to 80.0))
        assertEquals(FlowClass.BELOW_MEAN, FlowClass.fromDischarge(3.0, d))
        assertEquals(FlowClass.NORMAL, FlowClass.fromDischarge(20.0, d))
        assertEquals(FlowClass.HQ5, FlowClass.fromDischarge(75.0, d))
        assertEquals(FlowClass.HQ10, FlowClass.fromDischarge(95.0, d))
        assertTrue(FlowClass.HQ1.isFlood && !FlowClass.NORMAL.isFlood)
    }
}
