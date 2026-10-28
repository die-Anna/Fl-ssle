package at.hochwasser.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.hochwasser.core.AlarmEvaluator
import at.hochwasser.core.VowisClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Prüft alle 15 Minuten (kürzer erlaubt Android nicht) die Pegel gegen die eingestellten Alarme.
 * Android kann die Prüfung im Energiesparmodus verzögern.
 */
class AlarmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = AlarmStore(applicationContext)
        val rules = store.rules()
        if (rules.isEmpty()) return Result.success()

        val stations = runCatching { VowisClient(StationRepository.USER_AGENT).fetchStations() }
            .getOrElse { return Result.retry() }
        val (hits, active) = AlarmEvaluator.evaluate(rules, stations.map { it.reading }, store.alerted)
        store.alerted = active

        hits.forEach { hit ->
            val r = hit.reading
            val text = buildString {
                append("${r.river}: ${hit.flowClass.label}")
                r.waterLevelCm?.let { append(", $it cm") }
                r.dischargeM3s?.let { append(", ${String.format(Locale.GERMAN, "%.1f", it)} m³/s") }
            }
            notify(applicationContext, r.hzbNr.hashCode(), "Hochwasser-Alarm: ${r.name}", text)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "alarm-check"
        const val CHANNEL_ID = "alarms"

        /** Startet oder stoppt die Hintergrundprüfung, je nachdem ob Alarme eingestellt sind. */
        fun sync(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (AlarmStore(context).rules().isEmpty()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AlarmWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Hochwasser-Alarme", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Meldungen, wenn ein Pegel die eingestellte Schwelle erreicht"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun notify(context: Context, id: Int, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            createChannel(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        }
    }
}
