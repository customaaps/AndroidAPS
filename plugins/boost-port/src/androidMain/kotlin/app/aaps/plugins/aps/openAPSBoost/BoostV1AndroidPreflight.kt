package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal

/**
 * One-way AndroidAPS input adapter for the determine_basal replay preflight.
 * Does not construct RT or APSResult and is never registered as an APS plugin.
 */
internal object BoostV1AndroidPreflight {
    fun inspect(
        glucose: GlucoseStatus,
        currentTemp: CurrentTemp,
        iobData: Array<IobTotal>,
        profile: BoostV1ApsTypeBoundary.Profile,
        currentTimeMs: Long,
        flatBgsDetected: Boolean
    ): BoostV1DetermineBasalPreflight.Result =
        BoostV1DetermineBasalPreflight.inspect(
            BoostV1AndroidTypeAdapter.snapshotInputs(
                glucose = glucose,
                temp = currentTemp,
                profile = profile,
                iob = iobData,
                currentTimeMs = currentTimeMs,
                flatBGsDetected = flatBgsDetected
            )
        )
}
