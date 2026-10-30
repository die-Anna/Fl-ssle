package at.hochwasser.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import at.hochwasser.core.VowisClient
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
        val last = prefs(context).getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last > 5 * 60_000L) refreshAll(context)
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
        fun render(context: Context, id: Int, station: Station?, message: String? = null) {
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
            }
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
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
        val stations = runCatching { VowisClient(StationRepository.USER_AGENT).fetchStations() }
            .getOrElse { return Result.retry() } // die alten Werte bleiben bis zum nächsten Versuch stehen
        ids.forEach { id ->
            val hzb = StationWidget.stationFor(applicationContext, id)
            val station = stations.firstOrNull { it.reading.hzbNr == hzb }
            StationWidget.render(applicationContext, id, station, if (station == null) "Pegel nicht gefunden" else null)
        }
        return Result.success()
    }
}
