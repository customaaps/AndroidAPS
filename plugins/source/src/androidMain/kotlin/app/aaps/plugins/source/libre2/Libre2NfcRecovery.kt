package app.aaps.plugins.source.libre2

import java.io.IOException

internal class Libre2SensorEndedException : IOException("Libre2 sensor has ended or was removed")

/** JNI reports decoder failures as IllegalStateException; NFC protocol fallback handles IO failures. */
internal fun decodeLibre2NfcResult(decode: () -> Array<Any>): Libre2DecodeResult {
    val reply = try {
        decode()
    } catch (failure: IllegalStateException) {
        throw IOException("Libre2 NFC decoding failed", failure)
    }
    return Libre2DecodeResult.fromNative(reply).also {
        if (it.status != 0) throw IOException("Libre2 NFC decoder status: ${it.status}")
    }
}

/** Retry the whole read/decode/streaming setup only for unknown-generation protocol failures. */
internal fun <T> readLibre2Generation(
    generation: Libre2Generation?,
    first: () -> T,
    second: () -> T,
): Pair<Libre2Generation, T> = when (generation) {
    Libre2Generation.FIRST -> Libre2Generation.FIRST to first()
    Libre2Generation.SECOND -> Libre2Generation.SECOND to second()
    null -> try {
        Libre2Generation.FIRST to first()
    } catch (firstFailure: IOException) {
        if (firstFailure is Libre2SensorEndedException) throw firstFailure
        try {
            Libre2Generation.SECOND to second()
        } catch (secondFailure: IOException) {
            secondFailure.addSuppressed(firstFailure)
            throw secondFailure
        }
    }
}

/** New streaming credentials retain the same sensor's delivery checkpoint. */
internal fun Libre2Pairing.withDeliveryCheckpoint(previous: Libre2Pairing?): Libre2Pairing =
    if (previous != null && uid.contentEquals(previous.uid) && patchInfo.contentEquals(previous.patchInfo))
        copy(lastSampleId = previous.lastSampleId, lastReadingMillis = previous.lastReadingMillis)
    else this
