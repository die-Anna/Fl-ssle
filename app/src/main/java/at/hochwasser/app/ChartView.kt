package at.hochwasser.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import at.hochwasser.core.TimeValue
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Einfaches Liniendiagramm für den Abfluss, mit MQ- und HQ-Linien.
 * Beschriftungen in sp, damit sie mit der eingestellten Schriftgröße wachsen.
 */
class ChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    data class Threshold(val label: String, val value: Double, val color: Int)

    private var values: List<TimeValue> = emptyList()
    private var thresholds: List<Threshold> = emptyList()
    private var timeFormat = DateTimeFormatter.ofPattern("dd.MM.")

    /** Kompakt: nur Kurve und Schwellen ohne Achsen, für die Vorschau in der Stationskarte. */
    var compact = false
        set(value) { field = value; invalidate() }

    /** Text, solange keine Kurve da ist (z.B. „Verlauf wird geladen …“). */
    var placeholder = "Keine Daten"
        set(value) { field = value; invalidate() }

    private val zone = ZoneId.of("Europe/Vienna")
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0"); style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#331565C0") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#E0E0E0"); strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#424242"); textSize = sp(13f) }
    private val labelBackground = Paint().apply { color = Color.parseColor("#D9FFFFFF") }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    fun setData(values: List<TimeValue>, thresholds: List<Threshold>, timePattern: String) {
        this.values = values
        this.thresholds = thresholds
        this.timeFormat = DateTimeFormatter.ofPattern(timePattern)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) {
            textPaint.color = Color.parseColor("#757575")
            canvas.drawText(placeholder, if (compact) 0f else dp(16f), height / 2f + textPaint.textSize / 3, textPaint)
            textPaint.color = Color.parseColor("#424242")
            return
        }
        if (compact) { drawCompact(canvas); return }
        val maxValue = values.maxOf { it.value }
        // HQ-Linien nur zeigen, wenn sie in der Nähe der Kurve liegen, sonst wird die Kurve zu flach
        val visible = thresholds.filter { it.value <= maxValue * 2.5 }
        val yMax = maxOf(maxValue, visible.maxOfOrNull { it.value } ?: 0.0) * 1.08
        val yMin = 0.0

        val left = textPaint.measureText("0000") + dp(8f)
        val right = width - dp(8f)
        val top = dp(8f)
        val bottom = height - textPaint.textSize - dp(10f)
        val t0 = values.first().time.toEpochMilli().toDouble()
        val t1 = values.last().time.toEpochMilli().toDouble()
        fun x(t: Double) = (left + (t - t0) / (t1 - t0) * (right - left)).toFloat()
        fun y(v: Double) = (bottom - (v - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

        // Gitter und y-Beschriftung
        val steps = 4
        for (i in 0..steps) {
            val v = yMin + (yMax - yMin) * i / steps
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, gridPaint)
            canvas.drawText(formatValue(v), dp(2f), yy + textPaint.textSize / 3, textPaint)
        }

        // Zeitachse: Anfang (linksbündig), Mitte, Ende (rechtsbündig), damit die Abstände gleich wirken
        for (i in 0..2) {
            val t = t0 + (t1 - t0) * i / 2
            val label = timeFormat.format(java.time.Instant.ofEpochMilli(t.toLong()).atZone(zone))
            val w = textPaint.measureText(label)
            val xx = when (i) {
                0 -> left
                1 -> x(t) - w / 2
                else -> right - w
            }
            canvas.drawText(label, xx, height - dp(4f), textPaint)
        }

        // Kurve
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, tv ->
            val px = x(tv.time.toEpochMilli().toDouble())
            val py = y(tv.value)
            if (i == 0) { path.moveTo(px, py); fill.moveTo(px, bottom); fill.lineTo(px, py) } else { path.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo(x(t1), bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)

        // Schwellen
        for (t in visible) {
            val yy = y(t.value)
            thresholdPaint.color = t.color
            canvas.drawLine(left, yy, right, yy, thresholdPaint)
            // Beschriftung mit hellem Hintergrund, damit sie auf der Kurve lesbar bleibt
            val label = "${t.label} ${formatValue(t.value)}"
            val w = textPaint.measureText(label)
            val lx = left + dp(4f)
            val ly = yy - dp(4f)
            canvas.drawRect(lx - dp(2f), ly - textPaint.textSize, lx + w + dp(2f), ly + dp(3f), labelBackground)
            textPaint.color = t.color
            canvas.drawText(label, lx, ly, textPaint)
            textPaint.color = Color.parseColor("#424242")
        }
    }

    private fun drawCompact(canvas: Canvas) {
        val minValue = values.minOf { it.value }
        val maxValue = values.maxOf { it.value }
        // Nur Schwellen zeigen, die knapp über der Kurve liegen, sonst wird die Kurve flach
        val visible = thresholds.filter { it.value <= maxValue * 1.3 }
        val yMax = maxOf(maxValue, visible.maxOfOrNull { it.value } ?: 0.0)
        val span = (yMax - minValue).takeIf { it > 0 } ?: 1.0
        val yMin = minValue - span * 0.15
        val yTop = yMax + span * 0.1
        val pad = dp(3f)
        val left = pad
        val right = width - pad
        val top = pad
        val bottom = height - pad
        val t0 = values.first().time.toEpochMilli().toDouble()
        val t1 = values.last().time.toEpochMilli().toDouble()
        fun x(t: Double) = (left + (t - t0) / (t1 - t0) * (right - left)).toFloat()
        fun y(v: Double) = (bottom - (v - yMin) / (yTop - yMin) * (bottom - top)).toFloat()

        for (t in visible) {
            thresholdPaint.color = t.color
            canvas.drawLine(left, y(t.value), right, y(t.value), thresholdPaint)
        }
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, tv ->
            val px = x(tv.time.toEpochMilli().toDouble())
            val py = y(tv.value)
            if (i == 0) { path.moveTo(px, py); fill.moveTo(px, bottom); fill.lineTo(px, py) } else { path.lineTo(px, py); fill.lineTo(px, py) }
        }
        fill.lineTo(x(t1), bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
        // Punkt am aktuellen Wert
        val last = values.last()
        canvas.drawCircle(x(t1), y(last.value), dp(4f), linePaint.apply { style = Paint.Style.FILL })
        linePaint.style = Paint.Style.STROKE
    }

    private fun formatValue(v: Double) =
        if (v >= 10) String.format(Locale.GERMAN, "%.0f", v) else String.format(Locale.GERMAN, "%.1f", v)
}
