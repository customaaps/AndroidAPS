package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

/**
 * Compare previously captured original and candidate AndroidAPS results.
 * Both results must come from independent runs on an identical stored input.
 * The adapter never invokes determine_basal or submits insulin commands.
 */
internal object BoostV1AndroidParityAdapter {
    data class Comparison(
        val parity: BoostV1ResultParity.Report,
        val sourceA: String,
        val sourceB: String,
        val isReadOnly: Boolean = true
    )

    fun compareRtToApsResult(reference: RT, candidate: APSResult): Comparison =
        compare(
            BoostV1AndroidTypeAdapter.fromRt(reference),
            BoostV1AndroidTypeAdapter.fromApsResult(candidate)
        )

    fun compareRt(reference: RT, candidate: RT): Comparison =
        compare(
            BoostV1AndroidTypeAdapter.fromRt(reference),
            BoostV1AndroidTypeAdapter.fromRt(candidate)
        )

    private fun compare(
        reference: BoostV1AndroidTypeAdapter.ReadOnlyResult,
        candidate: BoostV1AndroidTypeAdapter.ReadOnlyResult
    ): Comparison {
        fun asParityOutput(result: BoostV1AndroidTypeAdapter.ReadOnlyResult) =
            BoostV1ResultParity.Output(
                algorithm = result.algorithm.name,
                tempRequested = result.tempBasalRequested,
                rate = result.observedTempRate,
                durationMinutes = result.observedTempDuration,
                smbUnits = result.observedSmbUnits,
                targetBg = result.targetBg
            )

        return Comparison(
            parity = BoostV1ResultParity.compare(
                asParityOutput(reference), asParityOutput(candidate)
            ),
            sourceA = reference.source,
            sourceB = candidate.source
        )
    }
}
