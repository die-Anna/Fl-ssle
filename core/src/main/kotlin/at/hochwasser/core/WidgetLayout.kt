package at.hochwasser.core

import kotlin.math.max
import kotlin.math.min

/**
 * Plant, wie das Widget seinen Platz aufteilt. Wichtig sind Wert, Tendenz und Verlauf;
 * Ort und Uhrzeit kommen nur dazu, wenn noch Platz ist.
 *
 * Bei sehr großer Schrift wird die Schrift im Widget so weit verkleinert (höchstens auf [MIN_SCALE]),
 * dass der Verlauf sichtbar bleibt. Alle Maße in dp, Schriftgrößen in sp.
 */
object WidgetLayout {
    const val PADDING = 8
    const val ICON = 32
    const val GAP = 6
    const val VALUE_SP = 22f
    const val SUB_SP = 12f
    const val META_SP = 11f
    const val MIN_CHART_HEIGHT = 36
    const val MIN_INLINE_WIDTH = 56
    /** Kleinster Faktor (App-Schrift × System-Schrift), auf den das Widget die Schrift verkleinert. */
    const val MIN_SCALE = 0.8f

    private const val LINE = 1.2f
    private const val CHAR = 0.58f

    enum class Chart { NONE, INLINE, BELOW }

    data class Plan(
        /** Faktor für die App-Schriftgröße im Widget (ersetzt die Einstellung der App). */
        val textScale: Float,
        val chart: Chart,
        val chartWidth: Int,
        val chartHeight: Int,
        val showMeta: Boolean,
    )

    /**
     * @param userScale Schriftgröße aus den App-Einstellungen
     * @param systemScale Schriftgröße des Systems (wirkt auf sp zusätzlich)
     * @param value Text des großen Werts, z.B. „12,3 m³/s“
     * @param sub Text unter dem Wert, z.B. „steigend · normal“
     */
    fun plan(widthDp: Int, heightDp: Int, userScale: Float, systemScale: Float, value: String, sub: String): Plan {
        val innerW = widthDp - 2 * PADDING
        val innerH = heightDp - 2 * PADDING
        var s = userScale
        while (true) {
            val k = s * systemScale
            val rowH = max(ICON.toFloat(), (VALUE_SP + SUB_SP) * k * LINE)
            val metaH = META_SP * k * LINE + 2
            if (rowH <= innerH) {
                // Verlauf unter dem Wert, über die ganze Breite
                val below = innerH - rowH - 4
                if (below >= MIN_CHART_HEIGHT) {
                    val meta = below - metaH >= MIN_CHART_HEIGHT
                    val h = if (meta) below - metaH else below
                    return Plan(s, Chart.BELOW, innerW, h.toInt(), meta)
                }
                // Nur eine Zeile hoch: kleiner Verlauf rechts neben dem Wert
                val textW = max(value.length * VALUE_SP * k * CHAR, sub.length * SUB_SP * k * CHAR)
                val inlineW = innerW - ICON - GAP - textW - GAP
                if (inlineW >= MIN_INLINE_WIDTH) {
                    val meta = innerH - rowH >= metaH
                    return Plan(s, Chart.INLINE, inlineW.toInt(), min(rowH, 40f).toInt(), meta)
                }
            }
            val next = s * 0.9f
            if (next * systemScale < MIN_SCALE) break
            s = next
        }
        // Zu klein für einen Verlauf: dann lieber die gewählte Schriftgröße behalten
        val rowH = max(ICON.toFloat(), (VALUE_SP + SUB_SP) * userScale * systemScale * LINE)
        val meta = innerH - rowH >= META_SP * userScale * systemScale * LINE + 2
        return Plan(userScale, Chart.NONE, 0, 0, meta)
    }
}
