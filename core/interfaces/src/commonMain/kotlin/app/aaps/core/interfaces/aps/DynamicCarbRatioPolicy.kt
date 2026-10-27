package app.aaps.core.interfaces.aps

/** Shared APS input preparation; the profile itself is never edited. */
interface DynamicCarbRatioPolicy {
    data class Input(val baseCr: Double, val glucose: Double, val target: Double,
                     val profileIsf: Double, val atMs: Long)
    data class TddParts(val average7Days: Double?, val average1Day: Double?,
                        val last24Hours: Double?, val last4Hours: Double?, val last8To4Hours: Double?)
    data class Result(val carbRatio: Double, val reason: String = "", val applied: Boolean = false)

    suspend fun calculate(input: Input, knownTdd: TddParts? = null): Result

    /** Explicit default for direct construction/tests; production DI supplies the shared policy. */
    object Disabled : DynamicCarbRatioPolicy {
        override suspend fun calculate(input: Input, knownTdd: TddParts?) = Result(input.baseCr)
    }
}
