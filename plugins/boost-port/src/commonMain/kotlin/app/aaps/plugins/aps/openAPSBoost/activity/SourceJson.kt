package app.aaps.plugins.aps.openAPSBoost.activity

import kotlinx.serialization.json.*

/** Small KMP codec boundary for the source activity histories. Arithmetic stays in the source classes. */
internal class SourceJsonObject private constructor(private val values: MutableMap<String, JsonElement>) {
    constructor() : this(linkedMapOf())
    constructor(raw: String) : this(parseObject(raw))
    internal constructor(value: JsonObject) : this(value.toMutableMap())
    fun put(key: String, value: Any?): SourceJsonObject = apply { values[key] = element(value) }
    fun has(key: String): Boolean = key in values
    fun keys(): Iterator<String> = values.keys.iterator()
    fun opt(key: String): Any? = values[key]?.let(::primitive)
    fun getLong(key: String): Long = number(values.getValue(key)).toLong()
    fun getInt(key: String): Int = number(values.getValue(key)).toInt()
    fun getString(key: String): String = (values.getValue(key) as JsonPrimitive).content
    fun optLong(key: String, fallback: Long): Long = values[key]?.let { runCatching { number(it).toLong() }.getOrNull() } ?: fallback
    fun optInt(key: String, fallback: Int): Int = values[key]?.let { runCatching { number(it).toInt() }.getOrNull() } ?: fallback
    // JSONObject.optString stringifies its explicit NULL sentinel as "null"; only
    // an absent key gets the fallback. Retain this source persistence behaviour.
    fun optString(key: String, fallback: String): String = when (val value = values[key]) {
        null -> fallback
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
    fun getJSONArray(key: String): SourceJsonArray = SourceJsonArray(values.getValue(key) as JsonArray)
    fun getJSONObject(key: String): SourceJsonObject = SourceJsonObject(values.getValue(key) as JsonObject)
    fun optJSONArray(key: String): SourceJsonArray? = (values[key] as? JsonArray)?.let(::SourceJsonArray)
    fun optJSONObject(key: String): SourceJsonObject? = (values[key] as? JsonObject)?.let(::SourceJsonObject)
    internal fun json(): JsonObject = JsonObject(values.toMap())
    override fun toString(): String = json().toString()

    companion object {
        val NULL: JsonNull = JsonNull
        private fun parseObject(raw: String): MutableMap<String, JsonElement> {
            require(raw.length <= 1_048_576) { "Activity history exceeds size limit" }
            return Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        }
    }
}

internal class SourceJsonArray private constructor(private val values: MutableList<JsonElement>) {
    constructor() : this(mutableListOf())
    internal constructor(value: JsonArray) : this(value.toMutableList())
    fun put(value: Any?): SourceJsonArray = apply { values.add(element(value)) }
    fun length(): Int = values.size
    fun getLong(index: Int): Long = number(values[index]).toLong()
    fun getInt(index: Int): Int = number(values[index]).toInt()
    fun getJSONArray(index: Int): SourceJsonArray = SourceJsonArray(values[index] as JsonArray)
    fun getJSONObject(index: Int): SourceJsonObject = SourceJsonObject(values[index] as JsonObject)
    internal fun json(): JsonArray = JsonArray(values.toList())
    override fun toString(): String = json().toString()
}

private fun number(value: JsonElement): Number {
    val p = value as JsonPrimitive
    return p.longOrNull ?: p.doubleOrNull ?: error("Not a number: $p")
}

private fun primitive(value: JsonElement): Any = when (value) {
    JsonNull -> JsonNull
    is JsonPrimitive -> value.longOrNull ?: value.doubleOrNull ?: value.booleanOrNull ?: value.content
    else -> value
}

private fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is SourceJsonObject -> value.json()
    is SourceJsonArray -> value.json()
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> { require(value.toDouble().isFinite()); JsonPrimitive(value) }
    else -> error("Unsupported activity JSON value")
}
