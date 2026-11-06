package app.aaps.core.data.model

import app.aaps.core.data.time.systemUtcOffsetAt

/** AutoISF algorithm component values calculated every 5 minutes. */
data class AIV(
    var id: Long = 0,
    /** Milliseconds since the epoch. */
    override var timestamp: Long,
    /** Accelerometer-based ISF adjustment factor. */
    var acceIsf: Double,
    /** Blood Glucose-based ISF adjustment factor. */
    var bgIsf: Double,
    /** Post-Prandial ISF adjustment factor. */
    var ppIsf: Double,
    /** Drift ISF adjustment factor. */
    var driftIsf: Double,
    /** Duration-based ISF adjustment factor. */
    var duraIsf: Double,
    /** Final ISF value after all adjustments. */
    var finalIsf: Double,
    /** Effective IOB threshold. */
    var iobThEffective: Double,
    var utcOffset: Long = systemUtcOffsetAt(timestamp),
    var version: Int = 0,
    var dateCreated: Long = -1,
    var isValid: Boolean = true,
    var referenceId: Long? = null,
    var ids: IDs = IDs()
) : TimeStamped {

    fun contentEqualsTo(other: AIV): Boolean {
        return this === other || (
            timestamp == other.timestamp &&
                acceIsf == other.acceIsf &&
                bgIsf == other.bgIsf &&
                ppIsf == other.ppIsf &&
                driftIsf == other.driftIsf &&
                duraIsf == other.duraIsf &&
                finalIsf == other.finalIsf &&
                iobThEffective == other.iobThEffective &&
                isValid == other.isValid)
    }
}
