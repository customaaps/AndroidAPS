/*
 * LibreView protocol/serial encoding adapted from JugglucoNG bb5757761a:
 * net/libreview/libreview.cpp, newlibre3.cpp and share/serial.cpp.
 * Copyright (C) 2021 Jaap Korthals Altes. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.aaps.plugins.source.libre2

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal object Libre2LibreViewPayload {
    private const val alphabet = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"
    private val utc = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(java.time.ZoneOffset.UTC)
    private val local = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT)
    const val gateway = "FSLibreLink.Android"

    /** NFC UID byte order, as used by Juggluco's getserial. Libre 2 product family is 3. */
    fun serial(sensorId: String): String {
        require(sensorId.matches(Regex("[0-9A-F]{16}")))
        val b = sensorId.chunked(2).map { it.toInt(16) }
        val indexes = listOf(3, b[5] shr 3, (b[4] shr 6) or ((b[5] and 7) shl 2),
            (b[4] shr 1) and 31, ((b[4] and 1) shl 4) or (b[3] shr 4),
            ((b[3] and 15) shl 1) or (b[2] shr 7), (b[2] shr 2) and 31,
            ((b[2] and 3) shl 3) or (b[1] shr 5), b[1] and 31, b[0] shr 3, (b[0] shl 2) and 31)
        return indexes.map { alphabet[it] }.joinToString("")
    }

    fun sensorMask(sensorId: String): Long =
        serial(sensorId).drop(1).fold(0L) { result, char -> (result shl 5) or alphabet.indexOf(char).toLong() } shl 14

    fun bucket(id: Int): Int = ((id + 7) / 15) * 15

    /** Only complete 15-minute windows become history; missing minutes are never interpolated. */
    fun completed(readings: List<Libre2Reading>): List<List<Libre2Reading>> =
        readings.groupBy { it.sensorId }.values.flatMap { sensor ->
            val lastId = sensor.maxOf { it.sampleId }
            sensor.groupBy { bucket(it.sampleId) }.filter { (id, _) -> id > 0 && lastId >= id + 7 }.values
        }

    fun measurements(records: List<Libre2UploadRecord>, token: String, deviceId: String,
                     model: String, manufacturer: String, sdk: Int, now: Long): JSONObject {
        val readings = records.map { it.reading }
        require(readings.isNotEmpty() && readings.all { it.valid() })
        val currentIds = records.associate { it.reading.identity to it.currentRecordNumber }
        fun extended(timestamp: Long) = JSONObject().put("factoryTimestamp", utc.format(Instant.ofEpochMilli(timestamp)))
        fun timestamp(value: Long) = local.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
        val currents = JSONArray()
        readings.forEach { r ->
            val trend = when (r.trend) { 1 -> "FallingQuickly"; 2 -> "Falling"; 3 -> "Stable"; 4 -> "Rising"; 5 -> "RisingQuickly"; else -> "NotDetermined" }
            currents.put(JSONObject().put("valueInMgPerDl", r.mgdl)
                .put("extendedProperties", extended(r.timestamp).put("isViewed", "false")
                    .put("lowOutOfRange", (r.mgdl < 40).toString()).put("highOutOfRange", (r.mgdl > 500).toString())
                    .put("trendArrow", trend).put("isActionable", "true").put("isFirstAfterTimeChange", "false"))
                .put("recordNumber", currentIds.getValue(r.identity)).put("timestamp", timestamp(r.timestamp)))
        }
        val history = JSONArray()
        completed(readings).forEach { group ->
            val r = group.first()
            val time = group.map { it.timestamp }.average().toLong()
            history.put(JSONObject().put("valueInMgPerDl", group.map { it.mgdl }.average().roundToInt())
                .put("extendedProperties", extended(time).put("isFirstAfterTimeChange", false))
                .put("recordNumber", sensorMask(r.sensorId) or bucket(r.sampleId).toLong())
                .put("timestamp", timestamp(time)))
        }
        val starts = JSONArray()
        readings.distinctBy { it.sensorId }.forEach { r ->
            starts.put(JSONObject().put("type", "com.abbottdiabetescare.informatics.sensorstart")
                .put("extendedProperties", extended(r.sensorStart).put("gmin", "40").put("gmax", "500")
                    .put("latejoin", "false").put("puckGen", "3").put("wearDuration", "20160")
                    .put("warmupTime", "60").put("isStreaming", "true").put("productType", "3"))
                .put("recordNumber", sensorMask(r.sensorId)).put("timestamp", timestamp(r.sensorStart)))
        }
        val device = JSONObject().put("hardwareDescriptor", model).put("hardwareName", manufacturer)
            .put("modelName", "com.freestylelibre.app.ru").put("osType", "Android")
            .put("osVersion", sdk.toString()).put("uniqueIdentifier", deviceId)
        val log = JSONObject().put("device", device).put("capabilities", JSONArray(listOf(
            "currentGlucose", "scheduledContinuousGlucose", "unscheduledContinuousGlucose",
            "generic-com.abbottdiabetescare.informatics.sensorstart")))
            .put("currentGlucoseEntries", currents).put("scheduledContinuousGlucoseEntries", history)
            .put("unscheduledContinuousGlucoseEntries", JSONArray()).put("genericEntries", starts)
        listOf("bloodGlucoseEntries", "foodEntries", "insulinEntries", "ketoneEntries").forEach { log.put(it, JSONArray()) }
        val settings = JSONObject().put("factoryConfig", JSONObject().put("UOM", "mg/dL")).put("firmwareVersion", "2.12.0")
            .put("miscellaneous", JSONObject().put("isStreaming", true).put("selectedLanguage", "ru-RU")
                .put("valueGlucoseTargetRangeLowInMgPerDl", 70).put("valueGlucoseTargetRangeHighInMgPerDl", 180)
                .put("selectedTimeFormat", "24hr").put("selectedCarbType", "grams of carbs"))
            .put("timestamp", timestamp(now))
        return envelope(token).put("DeviceData", JSONObject().put("connectedDevices", JSONObject().put("insulinDevices", JSONArray()))
            .put("deviceSettings", settings).put("forceUpload", false).put("header", JSONObject().put("device", device))
            .put("measurementLog", log))
    }

    fun envelope(token: String) = JSONObject().put("UserToken", token).put("Domain", "Libreview").put("GatewayType", gateway)
}
