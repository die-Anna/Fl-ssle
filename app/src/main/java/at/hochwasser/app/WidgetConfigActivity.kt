package at.hochwasser.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import at.hochwasser.core.Station
import kotlinx.coroutines.launch

/** Wird beim Hinzufügen des Widgets geöffnet: hier wählt man den Pegel, der Hauptpegel steht oben. */
class WidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var box: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Abbrechen (Zurück) soll das Widget nicht anlegen
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))
            addView(box)
        })
        box.addView(text("Pegel für das Widget", 22f, bold = true))
        box.addView(text(getString(R.string.loading), 15f, muted = true))

        lifecycleScope.launch {
            runCatching { StationRepository().load() }
                .onSuccess { show(it) }
                .onFailure { box.addView(text("Fehler beim Laden: ${it.message}", 15f, muted = true)) }
        }
    }

    private fun show(stations: List<Station>) {
        box.removeViews(1, box.childCount - 1)
        val favorite = getSharedPreferences("settings", MODE_PRIVATE).getString("favorite_hzb", null)
        val current = StationWidget.stationFor(this, widgetId) ?: favorite
        val sorted = stations.sortedWith(compareBy<Station> { it.reading.hzbNr != current }
            .thenBy { it.reading.river }.thenBy { it.reading.name })
        for (s in sorted) {
            val r = s.reading
            val star = if (r.hzbNr == favorite) "★ " else ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { choose(r.hzbNr) }
                addView(text(star + r.name, 18f, bold = true))
                addView(text(r.river, 15f, muted = true))
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun choose(hzbNr: String) {
        StationWidget.setStation(this, widgetId, hzbNr)
        StationWidget.render(this, widgetId, null)
        StationWidget.refreshAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun text(t: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(ContextCompat.getColor(context, if (muted) R.color.text_muted else R.color.text_strong))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
