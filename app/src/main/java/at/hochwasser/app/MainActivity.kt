package at.hochwasser.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import at.hochwasser.core.AlarmRule
import at.hochwasser.core.FlowClass
import at.hochwasser.core.Station
import at.hochwasser.core.TimeValue
import at.hochwasser.core.VowisClient
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nav: BottomNavigationView
    private lateinit var repository: StationRepository
    private lateinit var alarms: AlarmStore
    private val client = VowisClient(StationRepository.USER_AGENT)
    private var stations: List<Station> = emptyList()
    private var selected: Station? = null
    private var sparkJob: Job? = null
    private val sparkCache = mutableMapOf<String, List<TimeValue>>()
    private var locationOverlay: MyLocationNewOverlay? = null
    private var fitted = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                MaterialAlertDialogBuilder(this)
                    .setMessage("Ohne Erlaubnis für Benachrichtigungen kann die App keine Alarme anzeigen. " +
                        "Du kannst sie später in den Android-Einstellungen der App erlauben.")
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) showMyLocation()
            else Toast.makeText(this, "Ohne Standort-Erlaubnis kann die Karte dich nicht finden.", Toast.LENGTH_LONG).show()
        }

    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.of("Europe/Vienna"))
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Vienna"))

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid verlangt einen eigenen User-Agent für die OSM-Kacheln
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        repository = StationRepository()
        alarms = AlarmStore(applicationContext)
        AlarmWorker.createChannel(this)
        AlarmWorker.sync(this)
        map = findViewById(R.id.map)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        nav = findViewById(R.id.bottom_nav)

        applyMapStyle()
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 7.0
        map.maxZoomLevel = 18.0
        map.controller.setZoom(9.5)
        map.controller.setCenter(GeoPoint(47.22, 9.88))
        // Auf Vorarlberg zuschneiden, sobald die Kartengröße bekannt ist
        map.post { fitVorarlberg() }

        findViewById<ImageButton>(R.id.zoom_in).setOnClickListener { map.controller.zoomIn() }
        findViewById<ImageButton>(R.id.zoom_out).setOnClickListener { map.controller.zoomOut() }
        findViewById<ImageButton>(R.id.locate).setOnClickListener { locate() }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { nav.selectedItemId = R.id.nav_settings }
        findViewById<View>(R.id.sheet_main).setOnClickListener {
            selected?.let { startActivity(HistoryActivity.intent(this, it)) }
        }
        findViewById<ChartView>(R.id.sheet_chart).compact = true
        findViewById<ImageButton>(R.id.sheet_favorite).setOnClickListener { toggleFavorite() }

        nav.setOnItemSelectedListener { item -> showTab(item.itemId); true }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (nav.selectedItemId != R.id.nav_map) nav.selectedItemId = R.id.nav_map
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        findViewById<View>(R.id.legend).setOnClickListener { toggle(KEY_COLLAPSE_LEGEND) }
        findViewById<View>(R.id.sheet_handle).setOnClickListener { toggle(KEY_COLLAPSE_SHEET) }
        applyCollapsed()
        requested = intent.getStringExtra(StationWidget.EXTRA_HZB)
        refresh()
    }

    /** Vom Widget geöffnet: dessen Pegel auf der Karte zeigen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val hzb = intent.getStringExtra(StationWidget.EXTRA_HZB) ?: return
        nav.selectedItemId = R.id.nav_map
        val s = stations.firstOrNull { it.reading.hzbNr == hzb }
        if (s != null) select(s) else requested = hzb
    }

    /** Pegel, den das Widget beim Öffnen zeigen will. */
    private var requested: String? = null

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
        // Ohne eigenen Knopf: beim Zurückkehren neu laden, wenn die Daten älter als 10 Minuten sind
        if (lastLoad != 0L && System.currentTimeMillis() - lastLoad > 10 * 60_000L) refresh()
    }
    override fun onPause() { super.onPause(); map.onPause(); locationOverlay?.disableMyLocation() }

    private var lastLoad = 0L

    private fun refresh() {
        progress.visibility = View.VISIBLE
        status.setText(R.string.loading)
        statusNeeded = true
        updateStatusCard()
        lifecycleScope.launch {
            val result = runCatching { repository.load() }
            progress.visibility = View.GONE
            result.onSuccess { lastLoad = System.currentTimeMillis(); show(it) }
                .onFailure { status.text = "Fehler beim Laden: ${it.message}"; statusNeeded = true; updateStatusCard() }
        }
    }

    private fun show(stations: List<Station>) {
        this.stations = stations
        // Die Widgets gleich mit den frischen Werten neu zeichnen
        for (id in StationWidget.ids(this)) {
            StationWidget.stationFor(this, id)?.let { hzb ->
                stations.firstOrNull { it.reading.hzbNr == hzb }?.let { StationWidget.render(this, id, it) }
            }
        }
        sparkCache.clear()
        map.overlays.removeAll { it is Marker }
        val density = resources.displayMetrics.density
        // Hochwasser-Stationen zuletzt zeichnen, damit sie oben liegen
        for (s in stations.sortedBy { classOf(it) }) {
            val lat = s.details?.lat ?: continue
            val lon = s.details?.lon ?: continue
            val m = Marker(map)
            m.position = GeoPoint(lat, lon)
            m.icon = FlowColors.markerBitmap(classOf(s), density * markerScale(), s.reading.trendQ).toDrawable(resources)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.title = s.reading.name
            m.setOnMarkerClickListener { _, _ -> select(s); true }
            map.overlays.add(m)
        }
        map.invalidate()

        val latest = stations.mapNotNull { it.reading.measuredAt }.maxOrNull()
        val floods = stations.count { classOf(it).isFlood }
        status.text = buildString {
            append("${stations.size} Pegel")
            latest?.let { append(" · Stand ${timeFormat.format(it)}") }
            if (floods > 0) append("\n$floods über HQ1!")
        }
        // Die Statuszeile nur zeigen, wenn es eine Hochwasser-Warnung gibt
        statusNeeded = floods > 0
        updateStatusCard()

        // Ausgewählte Station behalten, sonst die kritischste zeigen
        val fromWidget = requested?.let { id -> stations.firstOrNull { it.reading.hzbNr == id } }
        requested = null
        val keep = fromWidget ?: selected?.let { old -> stations.firstOrNull { it.reading.hzbNr == old.reading.hzbNr } }
        val favorite = favorite()?.let { id -> stations.firstOrNull { it.reading.hzbNr == id } }
        (keep ?: favorite ?: stations.sortedWith(severity).firstOrNull())?.let { select(it, center = false) }
        if (!fitted) { fitted = true; map.post { fitVorarlberg() } }
        when (nav.selectedItemId) {
            R.id.nav_list -> buildList()
            R.id.nav_alarms -> buildAlarms()
        }
    }

    /**
     * Zeigt ganz Vorarlberg im freien Teil der Karte: Kopf und Statuskarte oben,
     * Legende und Stationskarte unten verdecken einen Teil, darum den Ausschnitt entsprechend erweitern.
     */
    private fun fitVorarlberg() {
        val h = map.height.toDouble()
        if (h <= 0) return
        val card = findViewById<View>(R.id.status_card)
        val top = if (card.visibility == View.VISIBLE) card.bottom.toDouble() else resources.getDimension(R.dimen.status_card_top).toDouble()
        val column = findViewById<View>(R.id.bottom_column)
        val bottom = h - (column.top + findViewById<View>(R.id.legend).top)
        val free = (1 - (top + bottom) / h).coerceAtLeast(0.25)
        // Sobald Daten da sind, auf die Pegel selbst zuschneiden, sonst auf die Landesgrenzen
        val lats = stations.mapNotNull { it.details?.lat }
        val lons = stations.mapNotNull { it.details?.lon }
        val area = if (lats.size >= 2) BoundingBox(lats.max(), lons.max(), lats.min(), lons.min()) else VORARLBERG
        val span = area.latNorth - area.latSouth
        val total = span / free
        val box = BoundingBox(
            area.latNorth + total * top / h, area.lonEast,
            area.latSouth - total * bottom / h, area.lonWest,
        )
        map.zoomToBoundingBox(box, false, 24)
    }

    private fun classOf(s: Station): FlowClass =
        s.reading.flowClass.takeUnless { it == FlowClass.UNKNOWN }
            ?: FlowClass.fromDischarge(s.reading.dischargeM3s, s.details)

    /** Höchste Klasse zuerst, bei Gleichstand der Abfluss im Verhältnis zum Mittelwasser. */
    private val severity = compareByDescending<Station> { classOf(it) }
        .thenByDescending { s ->
            val q = s.reading.dischargeM3s
            val mq = s.details?.meanDischargeM3s?.takeIf { it > 0 }
            if (q != null && mq != null) q / mq else 0.0
        }

    // --- Stationskarte unten ---

    private fun select(s: Station, center: Boolean = true) {
        selected = s
        val r = s.reading
        val cls = classOf(s)
        findViewById<View>(R.id.sheet).visibility = if (nav.selectedItemId == R.id.nav_map) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.sheet_name).text = r.name
        findViewById<TextView>(R.id.sheet_river).text = r.river
        findViewById<View>(R.id.sheet_icon).backgroundTintList =
            if (cls.isFlood) ColorStateList.valueOf(FlowColors.color(cls)) else null

        val discharge = r.dischargeM3s?.let { "${fmt(it)} m³/s" }
        findViewById<TextView>(R.id.sheet_value).text = levelText(r.waterLevelCm) ?: discharge ?: "–"

        val chip = findViewById<TextView>(R.id.sheet_class)
        chip.text = classLabel(cls)
        chip.setTextColor(chipTextColor(cls))
        chip.background = GradientDrawable().apply {
            cornerRadius = 14 * resources.displayMetrics.density
            setColor(withAlpha(FlowColors.color(cls), 0x40))
        }

        findViewById<TextView>(R.id.sheet_time).text = buildString {
            append("Aktuell")
            r.measuredAt?.let { append(" · ${clockFormat.format(it)} Uhr") }
            // Wenn oben der Wasserstand steht, den Abfluss hier zusätzlich zeigen
            if (r.waterLevelCm != null && discharge != null) append(" · $discharge")
        }
        updateFavoriteStar()
        findViewById<TextView>(R.id.sheet_trend).text = "Tendenz: ${trendText(r.trendQ)}"
        showTrendIcon(r.trendQ)

        if (center) {
            val lat = s.details?.lat
            val lon = s.details?.lon
            if (lat != null && lon != null) map.controller.animateTo(GeoPoint(lat, lon))
        }
        loadSparkline(s)
    }

    /** Abfluss der letzten drei Tage als kleine Kurve; wird pro Station zwischengespeichert. */
    private fun loadSparkline(s: Station) {
        val chart = findViewById<ChartView>(R.id.sheet_chart)
        val wisId = s.reading.wisId
        sparkJob?.cancel()
        val cached = wisId?.let { sparkCache[it] }
        chart.placeholder = if (wisId == null || cached != null) "Verlauf vom Land nicht bereitgestellt" else "Verlauf wird geladen …"
        chart.setData(cached ?: emptyList(), thresholds(s), "dd.MM.")
        if (wisId == null || cached != null) return
        sparkJob = lifecycleScope.launch {
            val to = Instant.now()
            val result = withContext(Dispatchers.IO) {
                runCatching { client.fetchDischargeHistory(wisId, to.minus(Duration.ofDays(3)), to) }
            }
            result.onFailure { Log.w(TAG, "Verlauf für $wisId nicht geladen", it) }
            val values = result.getOrDefault(emptyList())
            if (result.isSuccess) sparkCache[wisId] = values
            if (selected?.reading?.wisId == wisId) {
                // Für einzelne Pegel (z.B. Lorüns, Beschling) liefert vowis keine Zeitreihe
                chart.placeholder = if (result.isSuccess) "Verlauf vom Land nicht bereitgestellt" else "Verlauf konnte nicht geladen werden"
                chart.setData(values, thresholds(s), "dd.MM.")
            }
        }
    }

    private fun thresholds(s: Station): List<ChartView.Threshold> {
        val list = mutableListOf<ChartView.Threshold>()
        s.details?.meanDischargeM3s?.let { list += ChartView.Threshold("MQ", it, FlowColors.color(FlowClass.NORMAL)) }
        s.details?.hq?.forEach { (k, v) ->
            val cls = when (k) { 1 -> FlowClass.HQ1; 5 -> FlowClass.HQ5; 10 -> FlowClass.HQ10; 30 -> FlowClass.HQ30; 100 -> FlowClass.HQ100; else -> FlowClass.HQ300 }
            list += ChartView.Threshold("HQ$k", v, FlowColors.color(cls))
        }
        return list
    }

    // --- Reiter ---

    /** Statuszeile nur beim Laden, bei Fehlern und bei Hochwasser auf der Karte zeigen. */
    private var statusNeeded = true

    private fun updateStatusCard() {
        val onMap = nav.selectedItemId == R.id.nav_map
        findViewById<View>(R.id.status_card).visibility = if (onMap && statusNeeded) View.VISIBLE else View.GONE
    }

    private fun showTab(id: Int) {
        val onMap = id == R.id.nav_map
        findViewById<View>(R.id.panels).visibility = if (onMap) View.GONE else View.VISIBLE
        findViewById<View>(R.id.panel_list).visibility = if (id == R.id.nav_list) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panel_alarms).visibility = if (id == R.id.nav_alarms) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panel_settings).visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        for (v in listOf(R.id.map_controls, R.id.legend)) {
            findViewById<View>(v).visibility = if (onMap) View.VISIBLE else View.GONE
        }
        updateStatusCard()
        findViewById<View>(R.id.sheet).visibility = if (onMap && selected != null) View.VISIBLE else View.GONE
        when (id) {
            R.id.nav_list -> buildList()
            R.id.nav_alarms -> buildAlarms()
            R.id.nav_settings -> buildSettings()
        }
    }

    private fun buildList() {
        val box = findViewById<LinearLayout>(R.id.list_container)
        box.removeAllViews()
        box.addView(heading("Alle Pegel"))
        box.addView(hint("Sortiert nach Gefahr. Antippen zeigt den Pegel auf der Karte."))
        if (stations.isEmpty()) { box.addView(hint(getString(R.string.loading))); return }
        for (s in stations.sortedWith(severity)) {
            val r = s.reading
            val cls = classOf(s)
            val value = levelText(r.waterLevelCm) ?: r.dischargeM3s?.let { "${fmt(it)} m³/s" } ?: "–"
            box.addView(row(cls, r.name, "${r.river} · ${classLabel(cls)}", value) {
                nav.selectedItemId = R.id.nav_map
                map.controller.setZoom(maxOf(map.zoomLevelDouble, 12.0))
                select(s)
            })
        }
    }

    private fun buildAlarms() {
        val box = findViewById<LinearLayout>(R.id.alarms_container)
        box.removeAllViews()
        box.addView(heading(getString(R.string.alarms)))
        val rules = alarms.rules()
        box.addView(hint(
            if (rules.isEmpty()) "Noch keine Alarme. Die App prüft alle 15 Minuten und meldet sich, " +
                "wenn ein Pegel oder ein ganzer Fluss die gewählte Stufe erreicht."
            else "Die App prüft alle 15 Minuten. Zum Löschen auf den Mülleimer tippen."
        ))
        for (rule in rules) {
            val name = when (rule.scope) {
                AlarmRule.Scope.RIVER -> "Fluss ${rule.key}"
                AlarmRule.Scope.STATION -> stations.firstOrNull { it.reading.hzbNr == rule.key }?.reading?.name ?: "Pegel ${rule.key}"
            }
            val line = rowLayout(dp(8))
            line.addView(twoLines(name, rule.describe()), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val delete = ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                setBackgroundResource(selectableBackground())
                contentDescription = "Alarm löschen"
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage("Alarm „$name: ${rule.describe()}“ löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            alarms.remove(rule)
                            AlarmWorker.sync(this@MainActivity)
                            buildAlarms()
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
            }
            line.addView(delete, LinearLayout.LayoutParams(dp(48), dp(48)))
            box.addView(line)
        }
        val add = MaterialButton(this).apply {
            text = "Alarm hinzufügen"
            textSize = 16f
            isAllCaps = false
            setIconResource(R.drawable.ic_add)
            setOnClickListener { pickStationForAlarm() }
        }
        box.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun pickStationForAlarm() {
        if (stations.isEmpty()) { Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show(); return }
        val list = stations.sortedBy { it.reading.name }
        MaterialAlertDialogBuilder(this)
            .setTitle("Pegel wählen")
            .setItems(list.map { "${it.reading.name} (${it.reading.river})" }.toTypedArray()) { _, which -> chooseAlarmScope(list[which]) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun buildSettings() {
        val box = findViewById<LinearLayout>(R.id.settings_container)
        box.removeAllViews()
        box.addView(heading(getString(R.string.settings)))
        val fontLabel = FontScale.OPTIONS.firstOrNull { it.first == FontScale.get(this) }?.second ?: "Normal"
        box.addView(settingRow(getString(R.string.font_size), fontLabel) { chooseFontSize() })
        val favName = favorite()?.let { id -> stations.firstOrNull { it.reading.hzbNr == id }?.reading?.name ?: "Pegel $id" }
        box.addView(settingRow("Hauptpegel", favName ?: "keiner (Stern in der Stationskarte antippen)") { chooseFavorite() })
        box.addView(settingRow("Kartenstil", if (mapStyle() == STYLE_TOPO) "Gelände" else "Standard") { chooseMapStyle() })
        box.addView(settingRow(getString(R.string.alarms), "${alarms.rules().size} eingerichtet") { nav.selectedItemId = R.id.nav_alarms })
        box.addView(settingRow("Daten aktualisieren", status.text.toString()) {
            refresh()
            nav.selectedItemId = R.id.nav_map
        })
        box.addView(heading("Datenquellen"))
        box.addView(hint(getString(R.string.attribution)))
        box.addView(hint("Pegeldaten von vowis.vorarlberg.at. Das sind ungeprüfte Rohdaten. " +
            "Bei Hochwasser gelten die offiziellen Warnungen von Land und Gemeinde."))
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        box.addView(hint("Version $version"))
    }

    // --- Bausteine für Liste, Alarme und Einstellungen ---

    private fun row(cls: FlowClass, title: String, subtitle: String, value: String, onClick: () -> Unit): View {
        val line = rowLayout(dp(10)).clickable(onClick)
        val dot = ImageView(this)
        dot.setImageDrawable(FlowColors.markerBitmap(cls, resources.displayMetrics.density * markerScale()).toDrawable(resources))
        line.addView(dot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        line.addView(twoLines(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(text(value, 17f, bold = true))
        return line
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit): View {
        val line = rowLayout(dp(12)).clickable(onClick)
        line.addView(twoLines(title, value), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        return line
    }

    private fun rowLayout(vertical: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, vertical, 0, vertical)
    }

    private fun LinearLayout.clickable(onClick: () -> Unit) = apply {
        isClickable = true
        setBackgroundResource(selectableBackground())
        setOnClickListener { onClick() }
    }

    private fun twoLines(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 18f, bold = true))
        addView(text(subtitle, 15f, muted = true))
    }

    private fun heading(t: String) = text(t, 22f, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) }
    private fun hint(t: String) = text(t, 15f, muted = true).apply { setPadding(0, 0, 0, dp(8)) }

    private fun text(t: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(ContextCompat.getColor(context, if (muted) R.color.text_muted else R.color.text_strong))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun selectableBackground(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- Einklappen: Statuskarte, Legende und Stationskarte, Zustand bleibt gespeichert ---

    private fun collapsed(key: String) = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, false)

    private fun toggle(key: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, !collapsed(key)).apply()
        applyCollapsed()
    }

    private fun applyCollapsed() {

        val sheet = collapsed(KEY_COLLAPSE_SHEET)
        findViewById<View>(R.id.sheet_details).visibility = if (sheet) View.GONE else View.VISIBLE
        findViewById<View>(R.id.sheet_toggle).rotation = if (sheet) 180f else 0f

        buildLegend(compact = collapsed(KEY_COLLAPSE_LEGEND))
    }

    /** Eingeklappt zeigt die Legende nur die Farbpunkte, ausgeklappt auch die Namen. */
    private fun buildLegend(compact: Boolean) {
        val legend = findViewById<ViewGroup>(R.id.legend)
        legend.removeAllViews()
        legend.contentDescription = if (compact) "Legende ausklappen" else "Legende einklappen"
        val entries = listOf(
            FlowClass.LOW to "niedrig", FlowClass.NORMAL to "normal", FlowClass.HQ1 to "HQ1",
            FlowClass.HQ5 to "HQ5", FlowClass.HQ10 to "HQ10", FlowClass.HQ30 to "HQ30",
        )
        val density = resources.displayMetrics.density
        for ((cls, label) in entries) {
            val tv = TextView(this)
            tv.text = if (compact) "" else label
            tv.textSize = 14f
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_strong))
            tv.compoundDrawablePadding = (4 * density).toInt()
            val icon = FlowColors.markerBitmap(cls, density).toDrawable(resources)
            val s = (14 * density * markerScale()).toInt()
            icon.setBounds(0, 0, s, s)
            tv.setCompoundDrawables(icon, null, null, null)
            tv.setPadding(0, (2 * density).toInt(), (12 * density).toInt(), (2 * density).toInt())
            legend.addView(tv)
        }
    }

    // --- Standort ---

    private fun locate() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) showMyLocation()
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun showMyLocation() {
        val overlay = locationOverlay ?: MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
            locationOverlay = it
            map.overlays.add(it)
        }
        overlay.enableMyLocation()
        val zoom = maxOf(map.zoomLevelDouble, 12.0)
        val here = overlay.myLocation
        if (here != null) {
            map.controller.animateTo(here, zoom, 600L)
        } else {
            Toast.makeText(this, "Suche Standort …", Toast.LENGTH_SHORT).show()
            overlay.runOnFirstFix {
                runOnUiThread { overlay.myLocation?.let { map.controller.animateTo(it, zoom, 600L) } }
            }
        }
    }

    // --- Hauptpegel: wird beim Start geöffnet ---

    private fun favorite() = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FAVORITE, null)

    private fun setFavorite(hzbNr: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FAVORITE, hzbNr).apply()
    }

    private fun toggleFavorite() {
        val s = selected ?: return
        if (favorite() == s.reading.hzbNr) {
            setFavorite(null)
            Toast.makeText(this, "${s.reading.name} ist nicht mehr dein Hauptpegel", Toast.LENGTH_SHORT).show()
        } else {
            setFavorite(s.reading.hzbNr)
            Toast.makeText(this, "${s.reading.name} ist jetzt dein Hauptpegel und öffnet sich beim Start", Toast.LENGTH_LONG).show()
        }
        updateFavoriteStar()
    }

    private fun updateFavoriteStar() {
        val isFavorite = selected != null && favorite() == selected?.reading?.hzbNr
        findViewById<ImageButton>(R.id.sheet_favorite).apply {
            setImageResource(if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline)
            contentDescription = if (isFavorite) "Hauptpegel entfernen" else "Als Hauptpegel festlegen"
        }
    }

    private fun chooseFavorite() {
        if (stations.isEmpty()) { Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show(); return }
        val list = stations.sortedBy { it.reading.name }
        val labels = listOf("Keiner (auffälligsten Pegel zeigen)") + list.map { "${it.reading.name} (${it.reading.river})" }
        val checked = favorite()?.let { id -> list.indexOfFirst { it.reading.hzbNr == id } + 1 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Hauptpegel")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                setFavorite(if (which == 0) null else list[which - 1].reading.hzbNr)
                if (which > 0) select(list[which - 1], center = false)
                updateFavoriteStar()
                buildSettings()
            }
            .show()
    }

    // --- Kartenstil ---

    private fun mapStyle() = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MAP_STYLE, STYLE_STANDARD)

    private fun applyMapStyle() {
        map.setTileSource(if (mapStyle() == STYLE_TOPO) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
    }

    private fun chooseMapStyle() {
        val styles = arrayOf(STYLE_STANDARD, STYLE_TOPO)
        MaterialAlertDialogBuilder(this)
            .setTitle("Kartenstil")
            .setSingleChoiceItems(arrayOf("Standard (OpenStreetMap)", "Gelände (OpenTopoMap)"), styles.indexOf(mapStyle())) { dialog, which ->
                dialog.dismiss()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MAP_STYLE, styles[which]).apply()
                applyMapStyle()
                buildSettings()
            }
            .show()
    }

    // --- Alarme ---

    private fun chooseAlarmScope(s: Station) {
        val r = s.reading
        val options = arrayOf("Nur dieser Pegel (${r.name})", "Ganzer Fluss: ${r.river}")
        MaterialAlertDialogBuilder(this)
            .setTitle("Alarm für …")
            .setItems(options) { _, which ->
                if (which == 0) chooseThreshold(AlarmRule.Scope.STATION, r.hzbNr, r.name, r.waterLevelCm)
                else chooseThreshold(AlarmRule.Scope.RIVER, r.river, r.river, null)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun chooseThreshold(scope: AlarmRule.Scope, key: String, title: String, currentLevelCm: Int?) {
        val classes = listOf(FlowClass.NORMAL, FlowClass.HQ1, FlowClass.HQ5, FlowClass.HQ10, FlowClass.HQ30)
        val labels = mutableListOf("Kein Alarm")
        labels += classes.map { if (it == FlowClass.NORMAL) "Über Mittelwasser (MQ)" else "Ab ${it.label.substringBefore('–')}" }
        // Eigener Wasserstand ergibt nur für einen einzelnen Pegel Sinn
        if (scope == AlarmRule.Scope.STATION) labels += "Eigener Wasserstand (cm) …"

        val existing = alarms.find(scope, key)
        val checked = when {
            existing == null -> 0
            existing.minLevelCm != null -> labels.size - 1
            else -> classes.indexOf(existing.minClass) + 1
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alarm: $title")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> saveAlarm(AlarmRule(scope, key))
                    which <= classes.size -> saveAlarm(AlarmRule(scope, key, minClass = classes[which - 1]))
                    else -> askWaterLevel(scope, key, existing?.minLevelCm ?: currentLevelCm?.plus(50))
                }
            }
            .show()
    }

    private fun askWaterLevel(scope: AlarmRule.Scope, key: String, suggestion: Int?) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "z.B. 250"
        suggestion?.let { input.setText(it.toString()) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alarm ab Wasserstand (cm)")
            .setView(input)
            .setPositiveButton("Speichern") { _, _ ->
                input.text.toString().toIntOrNull()?.let { saveAlarm(AlarmRule(scope, key, minLevelCm = it)) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun saveAlarm(rule: AlarmRule) {
        alarms.put(rule)
        AlarmWorker.sync(this)
        if (rule.minClass != null || rule.minLevelCm != null) requestNotificationPermission()
        if (nav.selectedItemId == R.id.nav_alarms) buildAlarms()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun chooseFontSize() {
        val current = FontScale.get(this)
        val labels = FontScale.OPTIONS.map { it.second }.toTypedArray()
        val checked = FontScale.OPTIONS.indexOfFirst { it.first == current }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.font_size)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                FontScale.set(this, FontScale.OPTIONS[which].first)
                recreate()
            }
            .show()
    }

    /** Symbole auf der Karte wachsen mit der Schrift mit, aber gedämpft. */
    private fun markerScale(): Float = 1f + (resources.configuration.fontScale - 1f) * 0.5f

    private fun classLabel(c: FlowClass) = when (c) {
        FlowClass.UNKNOWN -> "unbekannt"
        FlowClass.LOW -> "niedrig"
        FlowClass.BELOW_MEAN -> "unter Mittel"
        FlowClass.NORMAL -> "normal"
        else -> "Hochwasser ${c.name}"
    }

    private fun chipTextColor(c: FlowClass) = when (c) {
        FlowClass.NORMAL -> Color.parseColor("#1B5E20")
        FlowClass.LOW, FlowClass.BELOW_MEAN -> Color.parseColor("#0D47A1")
        FlowClass.HQ1 -> Color.parseColor("#6D5300")
        FlowClass.UNKNOWN -> Color.parseColor("#424242")
        else -> FlowColors.color(c)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun levelText(cm: Int?) = cm?.let { String.format(Locale.GERMAN, "%.2f m", it / 100.0) }

    private fun trendText(trend: Double?): String = when {
        trend == null -> "unbekannt"
        trend > 0.01 -> "steigend"
        trend < -0.01 -> "fallend"
        else -> "gleichbleibend"
    }

    /** Pfeil im Kreis: schräg nach oben (orange) bei steigend, schräg nach unten (blau) bei fallend. */
    private fun showTrendIcon(trend: Double?) {
        val icon = findViewById<ImageView>(R.id.sheet_trend_icon)
        if (trend == null) { icon.visibility = View.GONE; return }
        icon.visibility = View.VISIBLE
        val (rotation, color) = when {
            trend > 0.01 -> -45f to "#FB8C00"
            trend < -0.01 -> 45f to "#1E88E5"
            else -> 0f to "#43A047"
        }
        icon.rotation = rotation
        icon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun fmt(v: Double) = String.format(Locale.GERMAN, "%.1f", v)

    companion object {
        private const val TAG = "Pegel"
        private val VORARLBERG = BoundingBox(47.60, 10.24, 46.84, 9.53)
        private const val PREFS = "settings"
        private const val KEY_MAP_STYLE = "map_style"
        private const val KEY_FAVORITE = "favorite_hzb"
        private const val KEY_COLLAPSE_LEGEND = "collapse_legend"
        private const val KEY_COLLAPSE_SHEET = "collapse_sheet"
        private const val STYLE_STANDARD = "standard"
        private const val STYLE_TOPO = "topo"
    }
}
