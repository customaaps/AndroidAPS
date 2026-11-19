package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.xdrip.XdripSourceResolver
import app.aaps.core.nssdk.localmodel.entry.Direction
import app.aaps.core.nssdk.localmodel.entry.NSSgvV3
import app.aaps.core.nssdk.localmodel.entry.NsUnits

// Nightscout displays sgv. When AAPS corrects it, unfiltered carries the source
// SGV so another AAPS can reconstruct the original input for local calibration.
private const val AAPS_CALIBRATED_SUFFIX = ":AAPS-calibrated"

fun NSSgvV3.toGV(): GV {
    val isAapsCalibrated = device?.endsWith(AAPS_CALIBRATED_SUFFIX) == true
    val sourceSgv = unfiltered?.takeIf { isAapsCalibrated && it.isFinite() && it > 0.0 }
    return GV(
        timestamp = date ?: throw IllegalArgumentException(),
        value = sourceSgv ?: sgv,
        noise = noise,
        raw = filtered,
        trendArrow = TrendArrow.fromString(direction?.nsName),
        ids = IDs(nightscoutId = identifier),
        // A Nightscout record may have been uploaded by xDrip, whose device field carries a prefix.
        sourceSensor = XdripSourceResolver.fromNightscoutDevice(device?.removeSuffix(AAPS_CALIBRATED_SUFFIX)),
        isValid = isValid,
        utcOffset = T.mins(utcOffset ?: 0L).msecs()
    )
}

fun GV.toNSSvgV3(calibratedSgv: Double? = null): NSSgvV3 {
    val corrected = calibratedSgv?.takeIf { it.isFinite() && it > 0.0 && value.isFinite() && value > 0.0 }
    return NSSgvV3(
        isValid = isValid,
        date = timestamp,
        utcOffset = T.msecs(utcOffset).mins(),
        filtered = raw,
        unfiltered = if (corrected != null) value else 0.0,
        sgv = corrected ?: value,
        units = NsUnits.MG_DL,
        direction = Direction.fromString(trendArrow.text),
        noise = noise,
        device = if (corrected == null) sourceSensor.text else sourceSensor.text + AAPS_CALIBRATED_SUFFIX,
        identifier = ids.nightscoutId
    )
}
