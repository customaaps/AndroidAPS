package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState
import app.aaps.plugins.aps.openAPSBoostV5.V5StateStoreOfflineBridge
import kotlinx.serialization.json.*

/** Source-shaped payloads in one versioned, replay-only envelope. No I/O. */
internal object BoostReplayStateCodec {
    data class Snapshot(
        val history: List<V1ReplayFeatures.CycleSnapshot> = emptyList(),
        val v5: V5PersistedState = V5PersistedState(),
    )

    private val integerFields = setOf("mealHypothesisAge", "mlMealLikelyNullStreak")
    private val clockFields = setOf("mealHypothesisLastAgeMs", "lastCommitMs", "nonPositiveRunStartMs",
        "maxScoreAtMs", "maxOffsetAtMs", "primerIobUpdatedMs")
    private val knownFields = checkNotNull(V5StateStoreOfflineBridge.toFields(V5PersistedState())).keys

    fun encode(snapshot: Snapshot): String? {
        val fields = V5StateStoreOfflineBridge.toFields(snapshot.v5) ?: return null
        if (snapshot.history.size > V1ReplayFeatures.LOOKBACK || snapshot.history.any { !valid(it) }) return null
        val state = buildJsonObject {
            fields.forEach { (key, value) ->
                when (key) {
                    "mealHypothesis" -> put(key, value)
                    "committedInSession" -> put(key, value.toBooleanStrict())
                    in integerFields -> put(key, value.toInt())
                    in clockFields -> put(key, value.toLong())
                    else -> put(key, value.toDouble())
                }
            }
        }
        val history = buildJsonArray {
            snapshot.history.forEach { s -> add(buildJsonObject {
                put("ts", s.ts); put("cgm_mgdl", s.cgmMgdl); put("iob_iob", s.iobIob)
                put("iob_activity", s.iobActivity); put("sug_eventualBG", s.sugEventualBG)
                put("recent_smb_units_60m", s.recentSmbUnits60m); put("sug_minDelta", s.sugMinDelta)
            }) }
        }
        return buildJsonObject { put("schema", 1); put("v1History", history); put("v5State", state) }.toString()
    }

    fun decode(raw: String): Snapshot? = try {
        val obj = boundedJson(raw)?.jsonObject
        if (obj?.get("schema") != JsonPrimitive(1)) null
        else restore(obj.getValue("v1History").jsonArray, obj.getValue("v5State").jsonObject)
    } catch (_: Exception) { null }

    /** Explicit import of valid source blobs. Corruption is distinct from a fresh session. */
    fun fromLegacy(history: String, v5: String): Snapshot? { return try {
        val ring = if (history.isBlank()) JsonArray(emptyList()) else boundedJson(history)?.jsonArray ?: return null
        val state = if (v5.isBlank()) buildJsonObject {
            put("mealHypothesis", "IDLE"); put("mealHypothesisAge", 0)
        } else boundedJson(v5)?.jsonObject ?: return null
        restore(ring, state)
    } catch (_: Exception) { null } }

    private fun restore(history: JsonArray, state: JsonObject): Snapshot? {
        val fields = mutableMapOf<String, String>()
        for (key in knownFields) {
            if (key !in state) continue
            val value = state[key] as? JsonPrimitive ?: return null
            if (value is JsonNull) return null
            val typed = when (key) {
                "mealHypothesis" -> value.isString
                "committedInSession" -> !value.isString && value.booleanOrNull != null
                in integerFields -> !value.isString && value.intOrNull != null
                in clockFields -> !value.isString && value.longOrNull != null
                else -> !value.isString && value.doubleOrNull?.isFinite() == true
            }
            if (!typed) return null
            fields[key] = value.content
        }
        val restored = V5StateStoreOfflineBridge.fromFields(fields) ?: return null
        if (history.size > 128) return null
        val ring = V1ReplayFeatures.RingBuffer()
        for (element in history) {
            val row = element.jsonObject
            fun number(key: String): Double {
                val value = row.getValue(key).jsonPrimitive
                require(!value.isString)
                return value.double.also { require(it.isFinite()) }
            }
            val ts = row.getValue("ts").jsonPrimitive
            if (ts.isString) return null
            val s = V1ReplayFeatures.CycleSnapshot(ts.long, number("cgm_mgdl"), number("iob_iob"),
                number("iob_activity"), number("sug_eventualBG"), number("recent_smb_units_60m"), number("sug_minDelta"))
            if (!valid(s)) return null
            ring.push(s)
        }
        return Snapshot(ring.snapshots.toList(), restored)
    }

    private fun valid(s: V1ReplayFeatures.CycleSnapshot) = s.ts >= 0L &&
        listOf(s.cgmMgdl, s.iobIob, s.iobActivity, s.sugEventualBG, s.recentSmbUnits60m, s.sugMinDelta).all { it.isFinite() }

    private fun boundedJson(raw: String): JsonElement? {
        if (raw.length > 32_000) return null
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 12) return null
                '}', ']' -> if (--depth < 0) return null
            }
        }
        if (depth != 0 || quoted) return null
        return Json.parseToJsonElement(raw)
    }
}
