package at.hochwasser.core

/**
 * Welche Pegel meint ein Finger-Tipp auf der Karte?
 *
 * Die Trefferfläche ist viel größer als der gezeichnete Punkt. Liegt nur ein Pegel darin,
 * oder ist einer deutlich näher als alle anderen, wird er direkt genommen. Liegen mehrere
 * ähnlich nah, kommen alle (nach Abstand sortiert) zurück, damit die App fragen kann.
 */
object TapPicker {
    /**
     * @param distance Abstand des Elements zum Tipp in Pixeln
     * @param radius Trefferradius in Pixeln
     * @param clearRatio Der nächste Pegel gilt als eindeutig, wenn er höchstens so viel
     *   des Abstands des zweitnächsten hat (0.5 = halb so weit weg).
     * @return leer = nichts getroffen, ein Element = eindeutig, mehrere = nachfragen
     */
    fun <T> pick(items: List<T>, distance: (T) -> Float, radius: Float, clearRatio: Float = 0.5f): List<T> {
        val hits = items.map { it to distance(it) }.filter { it.second <= radius }.sortedBy { it.second }
        if (hits.size <= 1) return hits.map { it.first }
        val (first, second) = hits[0].second to hits[1].second
        return if (first <= second * clearRatio) listOf(hits[0].first) else hits.map { it.first }
    }
}
