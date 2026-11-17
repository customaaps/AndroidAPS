package app.aaps.plugins.source.libre2

import java.io.IOException

/** The fallback covers reading and decoding, not just enabling streaming after a successful read. */
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
