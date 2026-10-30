package at.hochwasser.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.hochwasser.core.FlowClass
import at.hochwasser.core.Station
import at.hochwasser.core.TimeValue
import at.hochwasser.core.VowisClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Widget für den Startbildschirm, das einen Pegel zeigt. Welcher, wird beim Hinzufügen gewählt
 * (vorausgewählt ist der Hauptpegel). Android aktualisiert es etwa alle 30 Minuten.
 */
class StationWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Android ruft onUpdate auch bei Paketänderungen auf, darum höchstens alle 5 Minuten neu laden
        // Nach einem App-Update immer laden, sonst bliebe das neue Widget auf „Lade Pegeldaten …“ stehen
        val last = prefs(context).getLong(KEY_LAST, 0L)
        val updated = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (System.currentTimeMillis() - last > 5 * 60_000L || updated > last) refreshAll(context)
    }

    /** Größe geändert: neu zeichnen, damit der Verlauf je nach Höhe erscheint oder verschwindet. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Mit den zuletzt geladenen Daten sofort neu zeichnen, das Nachladen dauert sonst einige Sekunden
        lastData[id]?.let { (station, history) -> render(context, id, station, null, history) }
        refreshAll(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(KEEPALIVE_NAME)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        ids.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    companion object {
        private const val PREFS = "widgets"
        private const val WORK_NAME = "widget-update"
        private const val KEEPALIVE_NAME = "widget-keepalive"
        private const val KEY_LAST = "last_refresh"
        const val EXTRA_HZB = "hzb"
        private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Vienna"))

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun key(id: Int) = "station_$id"

        fun stationFor(context: Context, id: Int): String? = prefs(context).getString(key(id), null)

        fun setStation(context: Context, id: Int, hzbNr: String) {
            prefs(context).edit().putString(key(id), hzbNr).apply()
        }

        /** Holt die Daten im Hintergrund und zeichnet danach alle Widgets neu. */
        fun refreshAll(context: Context) {
            prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            val wm = WorkManager.getInstance(context)
            // Bekanntes WorkManager-Problem: ist keine Arbeit mehr geplant, schaltet WorkManager einen
            // eigenen Empfänger ab, das gilt als Paketänderung und löst wieder onUpdate aus (Endlosschleife).
            // Eine weit in der Zukunft geplante Arbeit hält den Empfänger aktiv.
            wm.enqueueUniqueWork(
                KEEPALIVE_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetWorker>().setInitialDelay(3650, TimeUnit.DAYS).build(),
            )
            val request = OneTimeWorkRequestBuilder<WidgetWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, StationWidget::class.java))

        /** Zeichnet ein Widget; ohne [station] bleibt nur der Hinweis [message] stehen. */
        /** Zuletzt gezeichnete Daten pro Widget, solange die App im Speicher ist. */
        private val lastData = mutableMapOf<Int, Pair<Station, List<TimeValue>>>()

        fun render(context: Context, id: Int, station: Station?, message: String? = null, history: List<TimeValue> = emptyList()) {
            if (station != null) lastData[id] = station to history
            val views = RemoteViews(context.packageName, R.layout.widget_station)
            val scale = FontScale.get(context)
            views.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, 16f * scale)
            views.setTextViewTextSize(R.id.widget_detail, TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, 20f * scale)
            views.setTextViewTextSize(R.id.widget_class, TypedValue.COMPLEX_UNIT_SP, 13f * scale)

            val hzb = station?.reading?.hzbNr ?: stationFor(context, id)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_HZB, hzb)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )

            if (station == null) {
                views.setViewVisibility(R.id.widget_chart, View.GONE)
                views.setViewVisibility(R.id.widget_chart_inline, View.GONE)
                views.setTextViewText(R.id.widget_detail, message ?: context.getString(R.string.loading))
            } else {
                val r = station.reading
                val cls = r.flowClass.takeUnless { it == FlowClass.UNKNOWN }
                    ?: FlowClass.fromDischarge(r.dischargeM3s, station.details)
                val density = context.resources.displayMetrics.density
                views.setImageViewBitmap(R.id.widget_icon, FlowColors.markerBitmap(cls, density * 1.4f, r.trendQ))
                views.setTextViewText(R.id.widget_name, r.name)
                views.setTextViewText(R.id.widget_detail, buildString {
                    append(r.river)
                    r.measuredAt?.let { append(" · ${clockFormat.format(it)} Uhr") }
                    if (message != null) append(" · $message")
                })
                val discharge = r.dischargeM3s?.let { String.format(Locale.GERMAN, "%.1f m³/s", it) }
                val level = r.waterLevelCm?.let { String.format(Locale.GERMAN, "%.2f m", it / 100.0) }
                views.setTextViewText(R.id.widget_value, level ?: discharge ?: "–")
                views.setTextViewText(R.id.widget_class, classLabel(cls))
                views.setTextColor(R.id.widget_class, if (cls.isFlood) FlowColors.color(cls) else Color.parseColor("#52606D"))
                // Ab 2 Zeilen groß unter der Zeile, bei 1 Zeile klein zwischen Name und Wert
                val (widthDp, heightDp) = sizeDp(context, id)
                val tall = heightDp >= 130
                val big = if (tall) chartBitmap(context, station, history, widthDp - 24, (heightDp - 24 - 36 - 8).coerceIn(24, 120)) else null
                // Platz in der Zeile: ohne Symbol, Abstände und Wert etwa ein Drittel davon
                val inlineWidth = ((widthDp - 24 - 36 - 10 - 8 - 90) * 0.4).toInt()
                val small = if (!tall && inlineWidth >= 40) chartBitmap(context, station, history, inlineWidth, 32) else null
                if (big != null) views.setImageViewBitmap(R.id.widget_chart, big)
                if (small != null) views.setImageViewBitmap(R.id.widget_chart_inline, small)
                // Immer ausdrücklich setzen: der Launcher behält sonst die alte Kurve
                views.setViewVisibility(R.id.widget_chart, if (big != null) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_chart_inline, if (small != null) View.VISIBLE else View.GONE)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
        }

        /** Tatsächliche Widget-Größe in dp. Hochformat: schmalste Breite und größte Höhe, Querformat umgekehrt. */
        private fun sizeDp(context: Context, id: Int): Pair<Int, Int> {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val heightDp = options.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            return widthDp to heightDp
        }

        /** Zeichnet den Verlauf mit der kompakten Diagrammansicht der App in der gewünschten Größe (dp). */
        private fun chartBitmap(context: Context, station: Station, history: List<TimeValue>, widthDp: Int, heightDp: Int): Bitmap? {
            if (history.size < 2) return null
            val density = context.resources.displayMetrics.density
            val w = (widthDp * density).toInt()
            val h = (heightDp * density).toInt()
            val chart = ChartView(context)
            chart.compact = true
            chart.setData(hourly(history), chartThresholds(station), "dd.MM.")
            chart.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            chart.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            chart.draw(Canvas(bmp))
            return bmp
        }

        /** Stundenmittel statt 5-Minuten-Werten, sonst wird die kleine Kurve zu einem zackigen Band. */
        private fun hourly(values: List<TimeValue>): List<TimeValue> {
            val buckets = values.groupBy { it.time.epochSecond / 3600 }
            val smoothed = buckets.toSortedMap().map { (hour, list) ->
                TimeValue(Instant.ofEpochSecond(hour * 3600 + 1800), list.sumOf { it.value } / list.size)
            }
            // Der letzte Punkt soll der aktuelle Wert sein
            return smoothed.dropLast(1) + values.last()
        }

        /** Mittelwasser und HQ-Linien wie in der Stationskarte. */
        fun chartThresholds(s: Station): List<ChartView.Threshold> {
            val list = mutableListOf<ChartView.Threshold>()
            s.details?.meanDischargeM3s?.let { list += ChartView.Threshold("MQ", it, FlowColors.color(FlowClass.NORMAL)) }
            s.details?.hq?.forEach { (k, v) ->
                val cls = when (k) { 1 -> FlowClass.HQ1; 5 -> FlowClass.HQ5; 10 -> FlowClass.HQ10; 30 -> FlowClass.HQ30; 100 -> FlowClass.HQ100; else -> FlowClass.HQ300 }
                list += ChartView.Threshold("HQ$k", v, FlowColors.color(cls))
            }
            return list
        }

        private fun classLabel(c: FlowClass) = when (c) {
            FlowClass.UNKNOWN -> "unbekannt"
            FlowClass.LOW -> "niedrig"
            FlowClass.BELOW_MEAN -> "unter Mittel"
            FlowClass.NORMAL -> "normal"
            else -> "Hochwasser ${c.name}"
        }
    }
}

/** Lädt die Pegel einmal und zeichnet alle Widgets damit neu. */
class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ids = StationWidget.ids(applicationContext)
        if (ids.isEmpty()) return Result.success()
        val client = VowisClient(StationRepository.USER_AGENT)
        val stations = runCatching { client.fetchStations() }
            .getOrElse { return Result.retry() } // die alten Werte bleiben bis zum nächsten Versuch stehen
        ids.forEach { id ->
            val hzb = StationWidget.stationFor(applicationContext, id)
            val station = stations.firstOrNull { it.reading.hzbNr == hzb }
            // Verlauf der letzten 3 Tage; fehlt er (manche Pegel haben keinen), bleibt die Kurve weg
            val history = station?.reading?.wisId?.let { wisId ->
                val to = Instant.now()
                runCatching { client.fetchDischargeHistory(wisId, to.minus(Duration.ofDays(3)), to) }.getOrDefault(emptyList())
            } ?: emptyList()
            StationWidget.render(applicationContext, id, station, if (station == null) "Pegel nicht gefunden" else null, history)
        }
        return Result.success()
    }
}
