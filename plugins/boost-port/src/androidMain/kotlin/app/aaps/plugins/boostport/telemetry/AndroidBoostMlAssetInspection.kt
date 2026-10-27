package app.aaps.plugins.boostport.telemetry

import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * Explicit-call-only Android resource reader for the two bundled Boost
 * LightGBM JSON assets. It is not injected, not registered and not invoked
 * from AAPS's live APS/dosing path, a scheduler or a background service.
 *
 * Only immutable parser results and schema metadata are returned. This
 * class never reads CGM/IOB data, predicts risk, touches Preferences,
 * issues insulin/SMB recommendations or communicates with a pump.
 *
 * Production app assets reside at app/src/main/assets/boost; callers
 * must provide the APPLICATION context to resolve that asset namespace.
 */
internal class AndroidBoostMlAssetInspection(
    private val context: Context,
) {
    sealed interface Result {
        data class Ready(
            val model: BoostMlTreeDiagnostics.Model,
            val schema: BoostMlAssetSchemaAudit.Summary,
        ) : Result
        data class Unavailable(val reason: String) : Result
    }

    private companion object {
        const val MAX_BYTES = 1_000_000
    }

    private fun pathFor(kind: BoostMlAssetSchemaAudit.Kind): String = when (kind) {
        BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12 -> "boost/hypo_risk_model.json"
        BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD -> "boost/meal_likelihood_model.json"
    }

    /**
     * Bound input while reading, rather than allocating an unbounded asset
     * and checking its size only after all bytes have been read.
     */
    private fun readBoundedAsset(path: String): ByteArray? =
        context.applicationContext.assets.open(path).use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (result.size() > MAX_BYTES - count) return@use null
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }

    /**
     * No arbitrary asset path is accepted: callers select one of two known
     * source models. A malformed/corrupt asset yields an explicit missing
     * result and never a partially parsed tree list.
     */
    fun loadForOfflineInspection(kind: BoostMlAssetSchemaAudit.Kind): Result {
        val raw = try {
            readBoundedAsset(pathFor(kind))
        } catch (_: Exception) {
            return Result.Unavailable("asset-unreadable")
        }
        if (raw == null || raw.isEmpty())
            return Result.Unavailable("asset-size-invalid")

        return when (val parsed = BoostMlAssetParser.parse(kind, raw.toString(Charsets.UTF_8))) {
            is BoostMlAssetParser.Result.Ready -> Result.Ready(parsed.model, parsed.schema)
            is BoostMlAssetParser.Result.Unavailable -> Result.Unavailable(parsed.reason)
        }
    }
}
