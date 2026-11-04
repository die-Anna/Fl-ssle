package at.hochwasser.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetLayoutTest {
    private val value = "12,3 m³/s"
    private val sub = "steigend · normal"

    @Test
    fun normalSizeShowsChartBelowWithMeta() {
        val p = WidgetLayout.plan(250, 180, 1f, 1f, value, sub)
        assertEquals(WidgetLayout.Chart.BELOW, p.chart)
        assertEquals(1f, p.textScale)
        assertTrue(p.showMeta)
        assertTrue(p.chartHeight >= 60)
    }

    @Test
    fun hugeFontKeepsChartByShrinkingText() {
        // „Riesig“ in der App und große System-Schrift auf einem 3x2-Widget
        val p = WidgetLayout.plan(250, 130, 2f, 1.3f, value, sub)
        assertEquals(WidgetLayout.Chart.BELOW, p.chart)
        assertTrue(p.chartHeight >= WidgetLayout.MIN_CHART_HEIGHT)
        assertTrue(p.textScale < 2f)
        assertTrue(p.textScale > 1f, "Schrift soll nur so weit wie nötig kleiner werden")
    }

    @Test
    fun hugeFontWithoutShrinkingIfItFits() {
        val p = WidgetLayout.plan(300, 300, 2f, 1f, value, sub)
        assertEquals(WidgetLayout.Chart.BELOW, p.chart)
        assertEquals(2f, p.textScale)
    }

    @Test
    fun oneRowWidgetShowsInlineChart() {
        val p = WidgetLayout.plan(320, 70, 1f, 1f, value, sub)
        assertEquals(WidgetLayout.Chart.INLINE, p.chart)
        assertTrue(p.chartWidth >= WidgetLayout.MIN_INLINE_WIDTH)
        assertFalse(p.showMeta)
    }

    @Test
    fun tinyWidgetKeepsFontAndDropsChart() {
        val p = WidgetLayout.plan(140, 60, 1.6f, 1f, value, sub)
        assertEquals(WidgetLayout.Chart.NONE, p.chart)
        assertEquals(1.6f, p.textScale)
    }
}
