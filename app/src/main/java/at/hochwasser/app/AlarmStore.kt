package at.hochwasser.app

import android.content.Context
import at.hochwasser.core.AlarmRule
import at.hochwasser.core.FlowClass
import org.json.JSONArray
import org.json.JSONObject

/** Speichert die eingestellten Alarme und welche Pegel schon gemeldet wurden. */
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun rules(): List<AlarmRule> {
        val arr = JSONArray(prefs.getString(KEY_RULES, "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            runCatching {
                AlarmRule(
                    scope = AlarmRule.Scope.valueOf(o.getString("scope")),
                    key = o.getString("key"),
                    minClass = o.optString("minClass").takeIf { it.isNotEmpty() }?.let { FlowClass.valueOf(it) },
                    minLevelCm = if (o.has("minLevelCm")) o.getInt("minLevelCm") else null,
                )
            }.getOrNull()
        }
    }

    /** Ersetzt einen bestehenden Alarm für denselben Pegel/Fluss; null-Schwellen entfernen ihn. */
    fun put(rule: AlarmRule) {
        val others = rules().filterNot { it.scope == rule.scope && it.key == rule.key }
        val keep = if (rule.minClass == null && rule.minLevelCm == null) others else others + rule
        save(keep)
    }

    fun remove(rule: AlarmRule) = save(rules() - rule)

    fun find(scope: AlarmRule.Scope, key: String): AlarmRule? =
        rules().firstOrNull { it.scope == scope && it.key == key }

    var alerted: Set<String>
        get() = prefs.getStringSet(KEY_ALERTED, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ALERTED, value).apply()

    private fun save(rules: List<AlarmRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            val o = JSONObject().put("scope", r.scope.name).put("key", r.key)
            r.minClass?.let { o.put("minClass", it.name) }
            r.minLevelCm?.let { o.put("minLevelCm", it) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_RULES = "rules"
        private const val KEY_ALERTED = "alerted"
    }
}
