package app.aaps.core.data.model

/**
 * Sources whose observation may be fed to the temporal estimator at its own cadence instead of the
 * interpolated 5-minute grid.
 *
 * Deliberately separate from [advancedFilteringSupported]: that list also gates SMB and AutoISF's raw
 * fit, so adding a source there has wider effects than a source-specific estimator input needs.
 *
 * Sibionics reports a sensor-state-calibrated observation every minute.
 */
fun SourceSensor.rawObservationEstimatorSupported(): Boolean = this in RAW_OBSERVATION_SOURCES

private val RAW_OBSERVATION_SOURCES = setOf(
    SourceSensor.SIBIONIC_GS1,
    SourceSensor.SIBIONIC_GS3,
    SourceSensor.SIBIONIC_UNKNOWN,
    // AiDex broadcasts every minute; feeding the estimator its own readings avoids the 5-minute
    // bucket interpolation. Newer senders name it `AidexX`, older ones `GlucoRx Aidex`.
    SourceSensor.AIDEX,
    SourceSensor.AIDEX_X,
)
