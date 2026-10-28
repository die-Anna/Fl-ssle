package at.hochwasser.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import at.hochwasser.core.FlowClass
import at.hochwasser.core.Station
import at.hochwasser.core.VowisClient
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.time.Duration
import java.time.Instant

/** Verlauf einer Station: Abfluss als eigenes Diagramm, Wasserstand als Grafik von vowis. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var chart: ChartView
    private lateinit var levelImage: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private val client = VowisClient(StationRepository.USER_AGENT)
    private var job: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        chart = findViewById(R.id.chart)
        levelImage = findViewById(R.id.level_image)
        progress = findViewById(R.id.progress)
        info = findViewById(R.id.info)

        findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_NAME)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.range)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            when (id) {
                R.id.range_3d -> load(Duration.ofDays(3), "dd.MM. HH:mm")
                R.id.range_30d -> load(Duration.ofDays(30), "dd.MM.")
                R.id.range_1y -> load(Duration.ofDays(365), "MM.yy")
            }
        }
        toggle.check(R.id.range_3d)
    }

    private fun load(span: Duration, pattern: String) {
        val wisId = intent.getStringExtra(EXTRA_WISID) ?: run {
            info.text = "Für diese Station ist kein Verlauf verfügbar."
            return
        }
        val to = Instant.now()
        val from = to.minus(span)
        job?.cancel()
        progress.visibility = View.VISIBLE
        info.text = if (span > Duration.ofDays(60)) "Lade ein Jahr Daten (einige MB) …" else "Lade Verlauf …"
        job = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.fetchDischargeHistory(wisId, from, to) }
            }
            progress.visibility = View.GONE
            result.onSuccess { values ->
                chart.setData(values, thresholds(), pattern)
                info.text = if (values.isEmpty()) "Keine Abflussdaten für diesen Zeitraum."
                else "Abfluss in m³/s · ${values.size} Werte" +
                    if (span > Duration.ofDays(4)) " (Höchstwert je ${if (span > Duration.ofDays(60)) "6 Stunden" else "Stunde"})" else ""
            }.onFailure { info.text = "Fehler beim Laden: ${it.message}" }

            // Wasserstand: vowis liefert dafür nur eine Grafik, keine Zahlen
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Jsoup.connect(VowisClient.waterLevelChartUrl(wisId, from, to))
                        .userAgent(StationRepository.USER_AGENT).ignoreContentType(true).maxBodySize(0)
                        .timeout(30_000).execute().bodyAsBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            levelImage.setImageBitmap(bmp)
            levelImage.visibility = if (bmp != null) View.VISIBLE else View.GONE
        }
    }

    private fun thresholds(): List<ChartView.Threshold> {
        val list = mutableListOf<ChartView.Threshold>()
        intent.getDoubleExtra(EXTRA_MQ, Double.NaN).takeUnless { it.isNaN() }?.let {
            list += ChartView.Threshold("MQ", it, FlowColors.color(FlowClass.NORMAL))
        }
        val hqKeys = intent.getIntArrayExtra(EXTRA_HQ_KEYS) ?: IntArray(0)
        val hqValues = intent.getDoubleArrayExtra(EXTRA_HQ_VALUES) ?: DoubleArray(0)
        hqKeys.zip(hqValues.toList()).forEach { (k, v) ->
            val cls = when (k) { 1 -> FlowClass.HQ1; 5 -> FlowClass.HQ5; 10 -> FlowClass.HQ10; 30 -> FlowClass.HQ30; 100 -> FlowClass.HQ100; else -> FlowClass.HQ300 }
            list += ChartView.Threshold("HQ$k", v, FlowColors.color(cls))
        }
        return list
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_WISID = "wisId"
        private const val EXTRA_MQ = "mq"
        private const val EXTRA_HQ_KEYS = "hqKeys"
        private const val EXTRA_HQ_VALUES = "hqValues"

        fun intent(context: Context, s: Station): Intent {
            val hq = s.details?.hq?.toSortedMap() ?: sortedMapOf()
            return Intent(context, HistoryActivity::class.java)
                .putExtra(EXTRA_NAME, "${s.reading.name} (${s.reading.river})")
                .putExtra(EXTRA_WISID, s.reading.wisId)
                .putExtra(EXTRA_MQ, s.details?.meanDischargeM3s ?: Double.NaN)
                .putExtra(EXTRA_HQ_KEYS, hq.keys.toIntArray())
                .putExtra(EXTRA_HQ_VALUES, hq.values.toDoubleArray())
        }
    }
}
