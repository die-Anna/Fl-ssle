package at.hochwasser.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Liest den Abfluss-Verlauf aus dem CSV-Download von vowis und dünnt lange Reihen aus. */
object VowisHistory {
    private val MEZ = ZoneOffset.ofHours(1)
    private val TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")

    fun parseCsv(text: String): List<TimeValue> =
        text.lineSequence().mapNotNull { line ->
            val cols = line.split(';')
            if (cols.size < 2) return@mapNotNull null
            val time = runCatching { LocalDateTime.parse(cols[0].trim(), TIME).toInstant(MEZ) }.getOrNull()
                ?: return@mapNotNull null
            val value = cols[1].trim().replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            TimeValue(time, value)
        }.toList()

    /**
     * Fasst Werte in Zeitfenstern zusammen und behält je Fenster den Höchstwert,
     * damit Hochwasserspitzen beim Ausdünnen nicht verloren gehen.
     */
    fun downsample(values: List<TimeValue>, bucketSeconds: Long): List<TimeValue> {
        if (bucketSeconds <= 0 || values.isEmpty()) return values
        return values.groupBy { it.time.epochSecond / bucketSeconds }
            .toSortedMap()
            .map { (bucket, vs) -> TimeValue(Instant.ofEpochSecond(bucket * bucketSeconds), vs.maxOf { it.value }) }
    }

    fun filterRange(values: List<TimeValue>, from: Instant, to: Instant): List<TimeValue> =
        values.filter { !it.time.isBefore(from) && !it.time.isAfter(to) }
}
