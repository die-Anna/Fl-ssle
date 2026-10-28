package at.hochwasser.app

import at.hochwasser.core.Station
import at.hochwasser.core.VowisClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holt alle Stationen mit Lage, aktuellen Werten und HQ-Kennwerten (zwei Anfragen an vowis). */
class StationRepository {
    private val client = VowisClient(USER_AGENT)

    suspend fun load(): List<Station> = withContext(Dispatchers.IO) { client.fetchStations() }

    companion object {
        const val USER_AGENT = "Fluessle/0.5 (Android; privat)"
    }
}
