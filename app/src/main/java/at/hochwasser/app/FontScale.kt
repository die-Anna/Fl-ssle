package at.hochwasser.app

import android.content.Context
import android.content.res.Configuration

/**
 * Eigene Schriftgröße der App, zusätzlich zur Systemeinstellung.
 * Wird mit der System-Schriftgröße multipliziert, damit beide zusammenwirken.
 */
object FontScale {
    val OPTIONS = listOf(1.0f to "Normal", 1.3f to "Groß", 1.6f to "Sehr groß", 2.0f to "Riesig")

    private const val PREFS = "settings"
    private const val KEY = "font_scale"

    fun get(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 1.0f)

    fun set(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply()
    }

    /** Für attachBaseContext: liefert einen Context mit angepasster Schriftgröße. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.fontScale *= get(base)
        return base.createConfigurationContext(config)
    }
}
