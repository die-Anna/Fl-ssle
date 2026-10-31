package at.hochwasser.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.util.TypedValue
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Flussläufe aus OpenStreetMap (assets/fluesse.json), kräftig blau über die Karte gezeichnet,
 * damit man die Flüsse neben den Pegeln gut sieht. Die Namen stehen entlang der Linie.
 */
class RiverLayer(
    private val context: Context,
    private val map: MapView,
    /** Lage der Pegel-Marker samt Radius in px: dort keine Namen hinschreiben. */
    private val markers: () -> List<Pair<GeoPoint, Float>>,
) {

    class River(val name: String, val major: Boolean, val lines: List<List<GeoPoint>>, val boxes: List<BoundingBox>)

    private val rivers: List<River> by lazy { load() }
    private val lines = mutableListOf<Pair<Polyline, Boolean>>()
    private val labels = LabelOverlay()
    private val density = context.resources.displayMetrics.density

    /** Linienstärke in dp; 0 = Flüsse ausgeblendet. */
    var width = 0f
        private set

    /** Linien und Namen unter die Pegel-Marker legen (bzw. entfernen, wenn die Stärke 0 ist). */
    fun apply(widthDp: Float) {
        width = widthDp
        map.overlays.removeAll { o -> o === labels || lines.any { it.first === o } }
        lines.clear()
        if (widthDp <= 0f) { map.invalidate(); return }
        val casing = mutableListOf<Polyline>()
        val core = mutableListOf<Polyline>()
        // Bäche schmäler als Flüsse; zuerst die weiße Kontur, darüber das Blau
        for (r in rivers.sortedBy { it.major }) for (pts in r.lines) {
            val w = widthDp * (if (r.major) 1f else 0.65f) * density
            casing += line(pts, Color.argb(200, 255, 255, 255), w + 3 * density)
            core += line(pts, RIVER_BLUE, w)
        }
        for (p in casing) lines += p to false
        for (p in core) lines += p to true
        map.overlays.addAll(0, lines.map { it.first } + labels)
        map.invalidate()
    }

    private fun line(pts: List<GeoPoint>, color: Int, w: Float) = Polyline(map, false, false).apply {
        setPoints(pts)
        outlinePaint.color = color
        outlinePaint.strokeWidth = w
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        infoWindow = null
        // Antippen soll den darunterliegenden Pegel nicht blockieren
        setOnClickListener { _, _, _ -> false }
    }

    private fun load(): List<River> = runCatching {
        val json = JSONObject(context.assets.open("fluesse.json").bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("rivers")
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val ls = r.getJSONArray("lines")
            val lines = (0 until ls.length()).map { j ->
                val c = ls.getJSONArray(j)
                (0 until c.length() / 2).map { k -> GeoPoint(c.getDouble(2 * k), c.getDouble(2 * k + 1)) }
            }
            River(r.getString("name"), r.optBoolean("major", true), lines, lines.map { BoundingBox.fromGeoPoints(it) })
        }
    }.getOrElse { emptyList() }

    /** Schreibt die Flussnamen entlang der Linie, weiß umrandet, und lässt sich nicht überlappen. */
    private inner class LabelOverlay : Overlay() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RIVER_BLUE_DARK
            isFakeBoldText = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
        }
        private val halo = Paint(fill).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4 * density
            strokeJoin = Paint.Join.ROUND
        }
        private val point = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || width <= 0f) return
            val zoom = mapView.zoomLevelDouble
            if (zoom < 7.5) return
            val view = mapView.boundingBox
            val proj = mapView.projection
            // Pegel-Marker liegen oben drauf und würden Namen sonst verdecken
            val taken = markers().mapNotNullTo(mutableListOf()) { (g, radius) ->
                proj.toPixels(g, point)
                RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
            }
            val spacing = 320 * density
            // Größere Flüsse zuerst, damit ihre Namen Vorrang haben
            for (r in rivers.sortedByDescending { it.major }) {
                if (!r.major && zoom < 11 || r.major && zoom < 10 && r.name !in OVERVIEW) continue
                val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (r.major) 14f else 12f, context.resources.displayMetrics)
                fill.textSize = sp
                halo.textSize = sp
                val textW = fill.measureText(r.name)
                // Name neben die Linie setzen, nicht darauf
                val lift = (width * (if (r.major) 1f else 0.65f) + 3) * density / 2 + 3 * density
                for ((i, pts) in r.lines.withIndex()) {
                    if (!intersects(r.boxes[i], view)) continue
                    val xs = FloatArray(pts.size)
                    val ys = FloatArray(pts.size)
                    for ((k, g) in pts.withIndex()) {
                        proj.toPixels(g, point)
                        xs[k] = point.x.toFloat(); ys[k] = point.y.toFloat()
                    }
                    var next = spacing / 2
                    var walked = 0f
                    for (k in 1 until pts.size) {
                        val seg = hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
                        while (walked + seg >= next) {
                            // Kein Platz: ein Stück weiter versuchen statt ganz auszulassen
                            next += if (placeLabel(c, r.name, textW, xs, ys, k - 1, next - walked, lift, taken)) spacing else 40 * density
                        }
                        walked += seg
                    }
                }
            }
        }

        /**
         * Schneidet ab der Stelle ein Stück Linie in Textlänge aus und schreibt den Namen daneben.
         * Bei steilen Abschnitten steht der Name waagrecht neben der Linie, sonst liest man ihn schlecht.
         */
        private fun placeLabel(c: Canvas, name: String, textW: Float, xs: FloatArray, ys: FloatArray, start: Int, offset: Float, lift: Float, taken: MutableList<RectF>): Boolean {
            val path = mutableListOf<Pair<Float, Float>>()
            var k = start
            var dx = xs[k + 1] - xs[k]; var dy = ys[k + 1] - ys[k]
            var len = hypot(dx, dy)
            val t = if (len > 0) offset / len else 0f
            path += (xs[k] + dx * t) to (ys[k] + dy * t)
            var need = textW + 8 * density
            var rest = len * (1 - t)
            while (true) {
                if (rest >= need) {
                    val f = (len - rest + need) / len
                    path += (xs[k] + dx * f) to (ys[k] + dy * f)
                    break
                }
                need -= rest
                k++
                path += xs[k] to ys[k]
                if (k + 1 >= xs.size) return false
                dx = xs[k + 1] - xs[k]; dy = ys[k + 1] - ys[k]
                len = hypot(dx, dy)
                rest = len
            }
            val (x0, y0) = path.first(); val (x1, y1) = path.last()
            val chord = hypot(x1 - x0, y1 - y0)
            val textH = fill.descent() - fill.ascent()
            val steep = abs(y1 - y0) > abs(x1 - x0) * 0.8f
            // Zu kurvig für Text entlang der Linie
            if (!steep && chord < textW * 0.85f) return false

            val candidates = if (steep) {
                // Waagrecht, rechts oder links direkt an der Linie
                val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
                listOf(
                    RectF(mx + lift, my - textH / 2, mx + lift + textW, my + textH / 2),
                    RectF(mx - lift - textW, my - textH / 2, mx - lift, my + textH / 2),
                )
            } else {
                val b = RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                path.forEach { (x, y) -> b.union(x, y) }
                b.top -= lift + textH
                listOf(b)
            }
            val bounds = candidates.firstOrNull { b ->
                val padded = RectF(b).apply { inset(-3 * density, -3 * density) }
                padded.left >= 0 && padded.top >= 64 * density && padded.right <= c.width && padded.bottom <= c.height &&
                    taken.none { RectF.intersects(it, padded) }
            } ?: return false
            taken += RectF(bounds).apply { inset(-3 * density, -3 * density) }

            if (steep) {
                val baseline = bounds.bottom - fill.descent()
                c.drawText(name, bounds.left, baseline, halo)
                c.drawText(name, bounds.left, baseline, fill)
            } else {
                // Immer von links nach rechts lesbar
                val ordered = if (x1 < x0) path.reversed() else path
                val p = Path()
                ordered.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                val vOffset = -lift - fill.descent()
                c.drawTextOnPath(name, p, 4 * density, vOffset, halo)
                c.drawTextOnPath(name, p, 4 * density, vOffset, fill)
            }
            return true
        }

        private fun intersects(a: BoundingBox, b: BoundingBox) =
            a.latSouth <= b.latNorth && a.latNorth >= b.latSouth && a.lonWest <= b.lonEast && a.lonEast >= b.lonWest
    }

    companion object {
        val RIVER_BLUE = Color.parseColor("#1E6FE0")
        val RIVER_BLUE_DARK = Color.parseColor("#0D47A1")
        /** Diese Namen stehen auch schon in der Übersicht über ganz Vorarlberg. */
        private val OVERVIEW = setOf("Rhein", "Ill", "Bregenzer Ach", "Lech", "Dornbirner Ache", "Frutz", "Alfenz", "Lutz", "Litz", "Subersach", "Breitach")
        val WIDTHS = listOf(0f to "Aus", 2f to "Dünn", 4f to "Mittel", 6f to "Dick", 9f to "Sehr dick")
    }
}
