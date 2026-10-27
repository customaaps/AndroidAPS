package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT

/**
 * Android target entry point for offline comparison of existing AAPS outputs.
 * It does not call determine_basal, mutate APSResult, or register as an APS.
 */
internal object BoostV1AndroidComparison {
    enum class Status {
        READY_FOR_COMPARISON, NO_TEMP_BASAL_REQUEST, INCOMPLETE_TEMP_BASAL_REQUEST, INVALID_INPUT
    }

    data class Report(
        val status: Status,
        val original: BoostV1AndroidTypeAdapter.ReadOnlyResult,
        val comparison: BoostV1ApsTypeBoundary.Comparison?,
        val mayDispatchToPump: Boolean = false
    )

    fun inspectRt(
        glucose: GlucoseStatus,
        currentTemp: CurrentTemp,
        profile: BoostV1ApsTypeBoundary.Profile,
        iobData: Array<IobTotal>,
        nowMs: Long,
        flatBgsDetected: Boolean,
        rt: RT
    ): Report = inspect(
        glucose, currentTemp, profile, iobData, nowMs, flatBgsDetected,
        BoostV1AndroidTypeAdapter.fromRt(rt)
    )

    fun inspectApsResult(
        glucose: GlucoseStatus,
        currentTemp: CurrentTemp,
        profile: BoostV1ApsTypeBoundary.Profile,
        iobData: Array<IobTotal>,
        nowMs: Long,
        flatBgsDetected: Boolean,
        apsResult: APSResult
    ): Report = inspect(
        glucose, currentTemp, profile, iobData, nowMs, flatBgsDetected,
        BoostV1AndroidTypeAdapter.fromApsResult(apsResult)
    )

    private fun inspect(
        glucose: GlucoseStatus,
        currentTemp: CurrentTemp,
        profile: BoostV1ApsTypeBoundary.Profile,
        iobData: Array<IobTotal>,
        nowMs: Long,
        flatBgsDetected: Boolean,
        original: BoostV1AndroidTypeAdapter.ReadOnlyResult
    ): Report {
        val snapshot = BoostV1AndroidTypeAdapter.snapshotInputs(
            glucose, currentTemp, profile, iobData, nowMs, flatBgsDetected
        )
        if (BoostV1ApsTypeBoundary.validate(snapshot) != BoostV1ApsTypeBoundary.Validation.VALID)
            return Report(Status.INVALID_INPUT, original, null)

        // Absence of a commanded rate is not the same as a zero rate.
        val rate = original.observedTempRate
        val duration = original.observedTempDuration
        if (!original.tempBasalRequested)
            return Report(Status.NO_TEMP_BASAL_REQUEST, original, null)
        if (rate == null || duration == null)
            return Report(Status.INCOMPLETE_TEMP_BASAL_REQUEST, original, null)
        val comparison = BoostV1ApsTypeBoundary.inspect(snapshot, rate, duration)
        return Report(
            if (comparison.validation == BoostV1ApsTypeBoundary.Validation.VALID)
                Status.READY_FOR_COMPARISON else Status.INVALID_INPUT,
            original,
            comparison
        )
    }
}
