package app.aaps.plugins.boostport.telemetry

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

/**
 * Concrete bridge to AndroidAPS's actual RT -> APSResult implementation.
 *
 * This adapter is intentionally NOT installed into the active loop. Its RT
 * contains no basal, bolus, insulin requirement or carbohydrate instruction.
 * An implementation of APSResult must be supplied by the caller.
 */
internal object BoostPortPassiveApsResultAdapter {
    enum class Finding { INVALID_TIMESTAMP, INVALID_REPORT, PASSIVE_RESULT_READY }
    data class Report(
        val finding: Finding,
        val value: APSResult? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun adapt(
        source: BoostV1V5OfflineIntegration.Report,
        timeMs: Long,
        newResult: () -> APSResult
    ): Report {
        if (timeMs <= 0L) return Report(Finding.INVALID_TIMESTAMP)
        if (source.finding != BoostV1V5OfflineIntegration.Finding.READY ||
            source.reviewedCycles <= 0)
            return Report(Finding.INVALID_REPORT)
        val rt = RT(
            algorithm = APSResult.Algorithm.UNKNOWN,
            runningDynamicIsf = false,
            timestamp = timeMs,
            reason = StringBuilder(
                if (source.riskReviewRequired)
                    "Boost offline historical replay: review required; not an insulin command"
                else
                    "Boost offline historical replay: no insulin command"
            )
        )
        val result = newResult().with(rt)
        // APSResult.with(RT) must never turn this historical diagnostic
        // into an actionable basal or bolus. Fail closed if it does.
        if (result.smb != 0.0 || result.isTempBasalRequested ||
            result.isBolusRequested || result.rate >= 0.0)
            return Report(Finding.INVALID_REPORT)
        return Report(Finding.PASSIVE_RESULT_READY, result)
    }
}
