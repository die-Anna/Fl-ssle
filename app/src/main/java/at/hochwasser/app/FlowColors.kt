package at.hochwasser.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import at.hochwasser.core.FlowClass

/** Farben angelehnt an die Legende auf vowis: blau = wenig, grün = normal, gelb bis violett = Hochwasser. */
object FlowColors {
    fun color(c: FlowClass): Int = when (c) {
        FlowClass.UNKNOWN -> Color.parseColor("#9E9E9E")
        FlowClass.LOW -> Color.parseColor("#90CAF9")
        FlowClass.BELOW_MEAN -> Color.parseColor("#42A5F5")
        FlowClass.NORMAL -> Color.parseColor("#43A047")
        FlowClass.HQ1 -> Color.parseColor("#FDD835")
        FlowClass.HQ5 -> Color.parseColor("#FB8C00")
        FlowClass.HQ10 -> Color.parseColor("#E53935")
        FlowClass.HQ30 -> Color.parseColor("#B71C1C")
        FlowClass.HQ100 -> Color.parseColor("#8E24AA")
        FlowClass.HQ300 -> Color.parseColor("#4A148C")
    }

    /**
     * Kreis-Symbol für die Karte; Hochwasser-Stationen werden größer gezeichnet.
     * Mit [trend] kommt ein Pfeil in den Kreis: schräg hoch = steigend, waagrecht = gleich, schräg runter = fallend.
     */
    fun markerBitmap(c: FlowClass, density: Float, trend: Double? = null): Bitmap {
        val dp = when {
            c.isFlood -> 30
            trend != null -> 24
            else -> 18
        }
        val size = (dp * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(r, r, r, paint)
        paint.color = color(c)
        canvas.drawCircle(r, r, r - 2.5f * density, paint)
        if (trend != null) drawArrow(canvas, r, size * 0.30f, angleFor(trend), arrowColor(c), density)
        return bmp
    }

    private fun angleFor(trend: Double) = when {
        trend > 0.01 -> -45f
        trend < -0.01 -> 45f
        else -> 0f
    }

    /** Auf hellen Farben (hellblau, gelb) ist ein dunkler Pfeil besser lesbar. */
    private fun arrowColor(c: FlowClass) = when (c) {
        FlowClass.LOW, FlowClass.HQ1, FlowClass.UNKNOWN -> Color.parseColor("#102A43")
        else -> Color.WHITE
    }

    private fun drawArrow(canvas: Canvas, center: Float, half: Float, angle: Float, color: Int, density: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.save()
        canvas.rotate(angle, center, center)
        val head = half * 0.6f
        val path = Path().apply {
            moveTo(center - half, center); lineTo(center + half, center)
            moveTo(center + half - head, center - head); lineTo(center + half, center); lineTo(center + half - head, center + head)
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
