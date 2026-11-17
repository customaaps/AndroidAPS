package app.aaps.ui.compose.overview

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.calibration.CalibrationContext
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.objects.extensions.fromGv

/** The newest sensor sample with the active display correction and its original timestamp. */
internal suspend fun PersistenceLayer.getLatestDisplayGlucose(
    config: Config,
    activePlugin: ActivePlugin
): InMemoryGlucoseValue? {
    val reading = getLastGlucoseValue()?.let { InMemoryGlucoseValue.fromGv(it) } ?: return null
    if (!config.initProgressFlow.value.done) return reading

    return activePlugin.activeCalibration
        .calibrate(mutableListOf(reading), CalibrationContext(checkForSensorGaps = false))
        .firstOrNull() ?: reading
}
