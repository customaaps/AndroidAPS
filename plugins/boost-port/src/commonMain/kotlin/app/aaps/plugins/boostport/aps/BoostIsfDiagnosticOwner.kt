package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.Round
import app.aaps.plugins.aps.openAPSBoost.replay.BoostIsfDiagnosticInput
import app.aaps.plugins.aps.openAPSBoost.replay.V1ReplayResult
import app.aaps.plugins.boostport.telemetry.BoostIsfEmaShadowDiagnostics as Ema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Sequential APS-owned, read-only comparison. Writes only the two source diagnostic keys.
 * Recreated after stop/import. Optional history or codec failures cannot alter the candidate.
 */
internal class BoostIsfDiagnosticOwner(private val store: KeyValueStore, private val tdd: TddCalculator) {
    suspend fun complete(result: RT, v1: V1ReplayResult, input: BoostIsfDiagnosticInput?, now: Long) {
        if (input == null || !input.isf.shadowEligible) return
        val emaKey = BoostApsSettingKeys.ApsBoostIsfShadowState.key
        val vwaKey = BoostApsSettingKeys.ApsBoostVwaTddShadowState.key
        val rawEma = store.getString(emaKey, "")
        val rawVwa = store.getString(vwaKey, "")
        var nextEma: String? = null
        var nextVwa: String? = null
        var comparison: Ema.Comparison? = null
        var vwaResult: BoostVwaTddDiagnostic.Result? = null
        try {
            val state = decodeEma(rawEma)
            comparison = Ema.compare(input.parts.last24Hours, input.parts.average7Days,
                input.settings.autosensMin, input.settings.autosensMax, now, state)
            nextEma = comparison?.nextState?.let(::encodeEma)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Abstain from this optional diagnostic. */ }
        try {
            validateVwa(rawVwa, now)
            val vwa = BoostVwaTddDiagnostic({ rawVwa }, { nextVwa = it })
            val sinceAnchorH = ((now - vwa.dayAnchorMs(now)) / 3_600_000L).coerceIn(0L, 24L)
            val delivered = if (sinceAnchorH > 0L) tdd.calculateDaily(now, -sinceAnchorH, 0L)?.totalAmount else 0.0
            // Preserve source hour-window semantics, including its 48 slices; no fabricated missing totals.
            vwa.warmFromHistory(now) { start, end ->
                // Source truncates half-hour offsets to whole hours. Its empty slices
                // contribute zero; target TDD returns null for an interval with no samples.
                // Only an exactly empty interval is zero. Missing nonempty history abstains.
                if (start == end) 0.0 else
                    tdd.calculateDaily(now, start, end)?.totalAmount?.takeIf { it.isFinite() && it >= 0.0 }
            }
            vwaResult = vwa.compute(now, delivered?.takeIf { it.isFinite() && it >= 0.0 }, input.parts.average7Days)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { nextVwa = null }
        currentCoroutineContext().ensureActive()
        check(store.getString(emaKey, "") == rawEma && store.getString(vwaKey, "") == rawVwa) {
            "Boost diagnostic state changed during history read"
        }
        // Commit both captures before publishing telemetry. A failed diagnostic write is isolated by the caller.
        if (nextEma != null || nextVwa != null) store.edit(commit = true) {
            nextEma?.let { putString(emaKey, it) }
            nextVwa?.let { putString(vwaKey, it) }
        }
        comparison?.let { shadow ->
            val scale = input.isf.ratio / shadow.shadowRatio
            if (scale.isFinite() && scale > 0.0) {
                result.isfShadow_ratioRaw = rounded(shadow.originalRawRatio, 0.001)
                result.isfShadow_ratioEma = rounded(shadow.shadowRatio, 0.001)
                result.isfShadow_warmup = rounded(shadow.warmupFraction, 0.01)
                result.isfShadow_variableSens = rounded(input.isf.variableSens * scale, 0.1)
                result.isfShadow_deltaPct = rounded((scale - 1.0) * 100.0, 0.01)
                // Source measures the V1 candidate, BEFORE the V6 dose override.
                result.isfShadow_insulinReq = v1.insulinReq?.let { rounded(it / scale, 0.001) }
                result.isfShadow_microBolus = v1.units?.let { rounded(it / scale, 0.001) }
                result.reason.append("IsfShadow: raw=${result.isfShadow_ratioRaw} ema=${result.isfShadow_ratioEma} warmup=${result.isfShadow_warmup}; ")
            }
        }
        vwaResult?.let { v ->
            result.boostVwa_blend = rounded(v.vwaBlend, 0.01)
            result.boostVwa_projection = rounded(v.projection, 0.01)
            result.boostVwa_expected = rounded(v.expectedToday, 0.01)
            result.boostVwa_delivered = rounded(v.deliveredToday, 0.01)
            result.boostVwa_dayFraction = rounded(v.dayFraction, 0.001)
            result.boostVwa_calibratedTdd = rounded(v.calibratedTdd, 0.01)
            result.boostVwa_curveDays = v.curveDays
            result.boostVwa_usedPrevDay = v.usedPreviousDay
            result.reason.append("${v.debugLine}; ")
        }
    }

    private fun rounded(value: Double, step: Double): Double? =
        value.takeIf { it.isFinite() && (it / step).isFinite() }?.let { Round.roundTo(it, step) }?.takeIf { it.isFinite() }

    internal companion object {
        fun decodeEma(raw: String): Ema.State {
            if (raw.isBlank()) return Ema.State()
            require(raw.length <= 4096)
            val o = Json.parseToJsonElement(raw).jsonObject
            fun time(key: String): Long? = (o[key]?.jsonPrimitive?.long ?: 0L).also { require(it >= 0L) }.takeIf { it != 0L }
            val ema = o["emaState"]?.jsonPrimitive?.double ?: 1.0
            require(ema.isFinite() && ema > 0.0)
            return Ema.State(ema, time("lastUpdateMs"), time("firstSeenMs"))
        }
        fun encodeEma(state: Ema.State) = buildJsonObject {
            put("emaState", state.ema); put("lastUpdateMs", state.lastUpdateMs ?: 0L); put("firstSeenMs", state.firstSeenMs ?: 0L)
        }.toString()
        fun validateVwa(raw: String, now: Long) {
            require(now > 0L)
            if (raw.isBlank()) return
            require(raw.length <= 65_536)
            val o = Json.parseToJsonElement(raw).jsonObject
            fun integer(key: String, default: Int = 0) = o[key]?.jsonPrimitive?.int ?: default
            require(integer("curveDays") in 0..1_000_000 && integer("warmedDays") in 0..7 && integer("anchorHour", 3) in 0..23)
            val start = o["dayStartMs"]?.jsonPrimitive?.long ?: 0L
            require(start in 0L..now)
            val previous = o["prevTotal"]?.jsonPrimitive?.double ?: 0.0
            require(previous.isFinite() && previous >= 0.0)
            for (key in listOf("curve", "dayBuckets")) o[key]?.let { value ->
                val a = value.jsonArray.map { it.jsonPrimitive.double }
                require(a.size == BoostVwaTddDiagnostic.BUCKETS && a.all { it.isFinite() && it >= 0.0 })
                if (key == "curve") require(a.all { it <= 1.0 } && a.last() > 0.0 && a.zipWithNext().all { it.first <= it.second })
            }
        }
    }
}
