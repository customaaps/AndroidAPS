package app.aaps.plugins.boostport.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bounded parser shared by Android asset reading and JVM replay. No automatic I/O. */
internal object BoostMlAssetParser {
    sealed interface Result {
        data class Ready(
            val model: BoostMlTreeDiagnostics.Model,
            val schema: BoostMlAssetSchemaAudit.Summary,
        ) : Result
        data class Unavailable(val reason: String) : Result
    }

    private class Budget(var parsedNodes: Int = 0)
    private const val MAX_BYTES = 1_000_000
    private const val MAX_NODES = 150_000
    private const val MAX_DEPTH = 64

    fun parse(kind: BoostMlAssetSchemaAudit.Kind, raw: String): Result {
        if (raw.isEmpty() || raw.length > MAX_BYTES || raw.encodeToByteArray().size > MAX_BYTES)
            return Result.Unavailable("asset-size-invalid")
        // Bound nesting before the JSON parser allocates/recurses over an untrusted document.
        if (!hasBoundedNesting(raw)) return Result.Unavailable("asset-json-invalid")
        return try {
            val parsed = Json.parseToJsonElement(raw).jsonObject
            val header = BoostMlAssetHeaderAudit.Header(
                declaredTrees = parsed.getValue("n_trees").jsonPrimitive.int,
                declaredFeatures = parsed.getValue("n_features").jsonPrimitive.int,
                objective = parsed.getValue("objective").jsonPrimitive.content,
                averageOutput = parsed.getValue("average_output").jsonPrimitive.boolean,
            )
            if (BoostMlAssetHeaderAudit.inspect(kind, header) == null)
                return Result.Unavailable("model-header-mismatch")
            val names = parsed.getValue("feature_names").jsonArray.map { it.jsonPrimitive.content }
            val expected = BoostMlAssetSchemaAudit.requiredFeatureNames(kind)
            if (names != expected) return Result.Unavailable("feature-schema-mismatch")
            val treesArray = parsed.getValue("trees").jsonArray
            if (treesArray.size != BoostMlAssetSchemaAudit.requiredTreeCount(kind))
                return Result.Unavailable("tree-count-mismatch")
            val budget = Budget()
            val model = BoostMlTreeDiagnostics.Model(names, treesArray.map { parseNode(it.jsonObject, 1, expected.size, budget) })
            val summary = BoostMlAssetSchemaAudit.inspect(kind, model)
                ?: return Result.Unavailable("model-invalid")
            Result.Ready(model, summary)
        } catch (_: Exception) {
            Result.Unavailable("asset-json-invalid")
        }
    }

    private fun hasBoundedNesting(raw: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in raw) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH + 4) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !inString
    }

    private fun parseNode(
        json: JsonObject, depth: Int, featureCount: Int, budget: Budget,
    ): BoostMlTreeDiagnostics.Node {
        require(depth <= MAX_DEPTH && ++budget.parsedNodes <= MAX_NODES)
        if ("leaf" in json) {
            val value = json.getValue("leaf").jsonPrimitive.double
            require(value.isFinite())
            return BoostMlTreeDiagnostics.Leaf(value)
        }
        val feature = json.getValue("feature").jsonPrimitive.int
        val threshold = json.getValue("threshold").jsonPrimitive.double
        require(json.getValue("decision_type").jsonPrimitive.content == "<=" &&
            feature in 0 until featureCount && threshold.isFinite())
        return BoostMlTreeDiagnostics.Split(feature, threshold,
            parseNode(json.getValue("left").jsonObject, depth + 1, featureCount, budget),
            parseNode(json.getValue("right").jsonObject, depth + 1, featureCount, budget))
    }
}
