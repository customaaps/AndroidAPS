package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.*
import app.aaps.core.keys.interfaces.NonPreferenceKey

/** APS boundary. No pump commands are issued by the calculator. Loop owns delivery constraints. */
interface BoostApsCalculator {
    data class Input(
        val glucose: GlucoseStatus,
        val currentTemp: CurrentTemp,
        val iob: Array<IobTotal>,
        val meal: MealData,
        val profile: OapsProfile,
        val originalProfileCr: Double,
        val originalProfileIsf: Double,
        val autosens: AutosensResult,
        val profilePercent: Int,
        val insulinPeak: Int,
        val atMs: Long,
        val microBolusAllowed: Boolean,
        val flatBg: Boolean,
        val pumpDisconnected: Boolean,
        val loopSuspended: Boolean,
        val profileSwitched: Boolean,
    )

    sealed interface Result {
        data class Calculated(
            val result: RT,
            val profile: OapsProfile,
            val autosens: AutosensResult,
        ) : Result
        data class Rejected(val reason: String) : Result
    }

    suspend fun calculate(input: Input): Result
    /** Stop/import invalidates this owner's access to state before preferences are replaced. */
    fun close() {}
}

interface BoostApsCalculatorFactory {
    val ownedKeys: List<NonPreferenceKey>
    /** A fresh owner is required after stop/import; persisted meal history is restored, not cleared. */
    fun create(): BoostApsCalculator
}

fun interface BoostApsModelSource {
    fun readAsset(name: String): String?
}
