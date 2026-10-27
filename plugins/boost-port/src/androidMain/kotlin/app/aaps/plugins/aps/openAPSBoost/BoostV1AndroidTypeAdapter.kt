package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT

/**
 * Android-only extraction of genuine AndroidAPS inputs for offline V1 comparison.
 * No APSResult.with(), plugin registration, pump dispatch or constraint bypass.
 */
internal object BoostV1AndroidTypeAdapter {

    fun snapshotInputs(
        glucose: GlucoseStatus,
        temp: CurrentTemp,
        profile: BoostV1ApsTypeBoundary.Profile,
        iob: Array<IobTotal>,
        currentTimeMs: Long,
        flatBGsDetected: Boolean
    ): BoostV1ApsTypeBoundary.Inputs {
        val noise = glucose.noise
        return BoostV1ApsTypeBoundary.Inputs(
            glucose = BoostV1CgmGateShadow.Input(
                currentTimeMs = currentTimeMs,
                glucoseTimeMs = glucose.date,
                bgMgDl = glucose.glucose,
                noise = if (noise.isFinite() && noise >= 0.0 && noise <= Int.MAX_VALUE.toDouble())
                    noise.toInt() else -1,
                flatBGsDetected = flatBGsDetected
            ),
            currentTempRate = temp.rate,
            currentTempMinutes = temp.duration,
            profile = profile,
            iob = iob.map { BoostV1ApsTypeBoundary.IobPoint(it.time, it.iob, it.activity) },
            glucoseDynamics = BoostV1ApsTypeBoundary.GlucoseDynamics(
                delta = glucose.delta,
                shortAvgDelta = glucose.shortAvgDelta,
                longAvgDelta = glucose.longAvgDelta,
                // Current AAPS GlucoseStatus does not expose a BG acceleration field.
                // Missing values stay explicitly absent rather than fabricated.
                bgAcceleration = null
            )
        )
    }

    /**
     * Snapshot the authentic RT/APSResult output; never copy instructions
     * into an active APSResult or expose an insulin delivery request.
     */
    data class ReadOnlyResult(
        val algorithm: APSResult.Algorithm,
        val reason: String,
        val eventualBg: Double?,
        val targetBg: Double?,
        val observedTempRate: Double?,
        val observedTempDuration: Int?,
        val observedSmbUnits: Double?,
        val source: String,
        val tempBasalRequested: Boolean,
        val eligibleForPumpDispatch: Boolean = false
    )

    fun fromRt(rt: RT): ReadOnlyResult = ReadOnlyResult(
        algorithm = rt.algorithm,
        reason = rt.reason.toString(),
        eventualBg = rt.eventualBG?.takeIf { it.isFinite() },
        targetBg = rt.targetBG?.takeIf { it.isFinite() },
        observedTempRate = rt.rate?.takeIf { it.isFinite() },
        observedTempDuration = rt.duration,
        observedSmbUnits = rt.units?.takeIf { it.isFinite() },
        source = "RT",
        tempBasalRequested = rt.rate != null || rt.duration != null
    )

    fun fromApsResult(result: APSResult): ReadOnlyResult = ReadOnlyResult(
        algorithm = result.algorithm,
        reason = result.reason,
        eventualBg = null,
        targetBg = result.targetBG.takeIf { it.isFinite() },
        observedTempRate = result.rate.takeIf { result.isTempBasalRequested && it.isFinite() },
        observedTempDuration = result.duration.takeIf { result.isTempBasalRequested },
        observedSmbUnits = result.smb.takeIf { it.isFinite() },
        source = "APSResult",
        tempBasalRequested = result.isTempBasalRequested
    )
}
