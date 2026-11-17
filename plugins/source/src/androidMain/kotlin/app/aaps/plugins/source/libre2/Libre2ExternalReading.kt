package app.aaps.plugins.source.libre2

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Never invent a sensor serial: external readings must match the NFC sensor and its start time. */
internal fun externalLibre2Reading(value: GV, sensorStart: Long, pairing: Libre2Pairing?, now: Long): Libre2Reading? {
    if (!value.isValid || value.sourceSensor != SourceSensor.LIBRE_2 || pairing == null) return null
    val start = pairing.state.sensorStartSeconds * 1000
    if (start <= 0 || sensorStart <= 0 || abs(start - sensorStart) > 300_000) return null
    val age = value.timestamp - start
    if (age < 0 || age > 14L * 24 * 60 * 60_000 || value.timestamp > now ||
        now - value.timestamp > Libre2UploadQueue.RETENTION_MS || !value.value.isFinite() || value.value !in 1.0..1000.0) return null
    val trend = when (value.trendArrow) {
        TrendArrow.TRIPLE_DOWN, TrendArrow.DOUBLE_DOWN -> 1
        TrendArrow.SINGLE_DOWN, TrendArrow.FORTY_FIVE_DOWN -> 2
        TrendArrow.FLAT -> 3
        TrendArrow.SINGLE_UP, TrendArrow.FORTY_FIVE_UP -> 4
        TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP -> 5
        else -> 0
    }
    return Libre2Reading(pairing.uid.joinToString("") { "%02X".format(it.toInt() and 255) },
        (age / 60_000).toInt(), value.timestamp, start, value.value.roundToInt(), trend)
}
