package app.aaps.database.persistence.converters

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.aps.GlucoseStatusSMB
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.OapsProfileAutoIsf
import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.constraints.Constraint
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import app.aaps.database.entities.APSResult as DbAPSResult

/**
 * Tests for `APSResultExtension.kt`.
 *
 * The supported algorithms are AMA, SMB, AUTO_ISF and BOOST. Both enum mappers deliberately `error()` on
 * UNKNOWN (the enum `else` branch), so UNKNOWN is excluded from the round-trip iteration and
 * asserted separately as a thrown exception.
 *
 * The data mapping (`fromDb` / `toDb`) is tested with [FakeApsResult], which holds only what the two
 * functions read and write.
 */
internal class APSResultExtensionTest {

    private val supportedDomain = APSResult.Algorithm.entries.filter { it != APSResult.Algorithm.UNKNOWN }
    private val supportedDb = DbAPSResult.Algorithm.entries.filter { it != DbAPSResult.Algorithm.UNKNOWN }

    @Test
    fun algorithmRoundTripFromDomain() {
        supportedDomain.forEach { algorithm ->
            assertEquals(algorithm, algorithm.toDb().fromDb())
        }
    }

    @Test
    fun algorithmRoundTripFromDb() {
        supportedDb.forEach { algorithm ->
            assertEquals(algorithm, algorithm.fromDb().toDb())
        }
    }

    @Test
    fun algorithmExplicitMapping() {
        assertEquals(DbAPSResult.Algorithm.AMA, APSResult.Algorithm.AMA.toDb())
        assertEquals(DbAPSResult.Algorithm.SMB, APSResult.Algorithm.SMB.toDb())
        assertEquals(DbAPSResult.Algorithm.AUTO_ISF, APSResult.Algorithm.AUTO_ISF.toDb())

        assertEquals(APSResult.Algorithm.AMA, DbAPSResult.Algorithm.AMA.fromDb())
        assertEquals(APSResult.Algorithm.SMB, DbAPSResult.Algorithm.SMB.fromDb())
        assertEquals(APSResult.Algorithm.AUTO_ISF, DbAPSResult.Algorithm.AUTO_ISF.fromDb())
    }

    @Test
    fun unknownAlgorithmThrows() {
        assertFailsWith<IllegalStateException> { APSResult.Algorithm.UNKNOWN.toDb() }
        assertFailsWith<IllegalStateException> { DbAPSResult.Algorithm.UNKNOWN.fromDb() }
    }

    // The APS plugins rebuild the DynISF 24 h average at start from the stored glucose status. It used to
    // be decoded as the interface, which always failed, so it was always null after a restart (#4589).
    @Test
    fun glucoseStatusIsReadBackForAmaAndSmb() {
        listOf(APSResult.Algorithm.AMA, APSResult.Algorithm.SMB, APSResult.Algorithm.BOOST).forEach { algorithm ->
            val glucoseStatus = GlucoseStatusSMB(glucose = 146.0, noise = 1.0, delta = -2.0, shortAvgDelta = -1.5, longAvgDelta = -1.0, date = 1_000L)

            val read = FakeApsResult(algorithm, glucoseStatus).toDb().fromDb { FakeApsResult() }

            assertEquals(glucoseStatus, read.glucoseStatus, "$algorithm")
        }
    }

    @Test
    fun glucoseStatusIsReadBackForAutoIsfWithItsOwnFields() {
        val glucoseStatus = GlucoseStatusAutoIsf(glucose = 146.0, delta = 3.0, date = 1_000L, duraISFminutes = 25.0, duraISFaverage = 140.0, bgAcceleration = 0.4, corrSqu = 0.95)

        val read = FakeApsResult(APSResult.Algorithm.AUTO_ISF, glucoseStatus).toDb().fromDb { FakeApsResult() }

        assertEquals(glucoseStatus, read.glucoseStatus)
    }

    @Test
    fun unreadableGlucoseStatusLosesOnlyThatField() {
        val stored = FakeApsResult(APSResult.Algorithm.SMB, GlucoseStatusSMB(glucose = 146.0)).toDb()
        stored.glucoseStatusJson = """{"delta":1.0}""" // "glucose" is required

        val read = stored.fromDb { FakeApsResult() }

        assertNull(read.glucoseStatus)
        assertEquals(19.7, (read.rawData() as RT).variable_sens)
    }

    @Test
    fun boostResultRetainsItsAlgorithmProfileAndSleepTelemetryAfterPersistence() {
        val glucose = GlucoseStatusSMB(glucose = 146.0, delta = 1.0, date = 1_000L)
        val rt = RT(algorithm = APSResult.Algorithm.BOOST, runningDynamicIsf = true,
            timestamp = 1_000L, units = 0.2, rate = 1.1, duration = 30, sleepState = "SLEEPING",
            sleepStateEnteredAtMs = 900L, sleepEntryReason = "hr", sleepLearnedSessionCount = 8,
            hrBpmAvg5m = 61.2, hrSource_resolved = "garmin", hrSource_states = "garmin(f,5,1m)")
        val original = FakeApsResult(APSResult.Algorithm.BOOST, glucose).apply {
            with(rt)
            oapsProfile = OapsProfile(dia = 5.0, min_5m_carbimpact = 8.0, max_iob = 5.0,
                max_daily_basal = 1.0, max_basal = 3.0, min_bg = 90.0, max_bg = 110.0, target_bg = 100.0,
                carb_ratio = 10.0, sens = 50.0, autosens_adjust_targets = false,
                max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
                high_temptarget_raises_sensitivity = true, low_temptarget_lowers_sensitivity = false,
                sensitivity_raises_target = false, resistance_lowers_target = false,
                adv_target_adjustments = false, exercise_mode = false, half_basal_exercise_target = 160,
                maxCOB = 120, skip_neutral_temps = true, remainingCarbsCap = 90, enableUAM = true,
                A52_risk_enable = false, SMBInterval = 3, enableSMB_with_COB = true,
                enableSMB_with_temptarget = true, allowSMB_with_high_temptarget = false,
                enableSMB_always = false, enableSMB_after_carbs = true, maxSMBBasalMinutes = 30,
                maxUAMSMBBasalMinutes = 30, bolus_increment = 0.05, carbsReqThreshold = 1,
                current_basal = 1.0, temptargetSet = false, autosens_max = 1.2, out_units = "mg/dl",
                lgsThreshold = null, variable_sens = 50.0, insulinDivisor = 55, TDD = 40.0)
            mealData = MealData(carbs = 20.0)
            autosensResult = AutosensResult(ratio = 0.9)
        }
        val stored = original.toDb()
        assertEquals(DbAPSResult.Algorithm.BOOST, stored.algorithm)
        val restored = stored.fromDb { FakeApsResult() }
        assertEquals(APSResult.Algorithm.BOOST, restored.algorithm)
        assertEquals(glucose, restored.glucoseStatus)
        assertEquals(original.oapsProfile, restored.oapsProfile)
        assertEquals(original.mealData, restored.mealData)
        assertEquals(original.autosensResult, restored.autosensResult)
        val restoredRt = restored.rawData() as RT
        assertEquals(rt, restoredRt.copy(reason = rt.reason))
    }

    /** Only the members `fromDb` and `toDb` use do something. The rest is never called by them. */
    private class FakeApsResult(
        override var algorithm: APSResult.Algorithm = APSResult.Algorithm.UNKNOWN,
        override var glucoseStatus: GlucoseStatus? = null
    ) : APSResult {

        private var rt = RT(algorithm = algorithm, runningDynamicIsf = true, timestamp = 1_000L, variable_sens = 19.7)

        override fun with(result: RT): APSResult = this.also {
            rt = result
            algorithm = result.algorithm
        }

        override fun rawData(): Any = rt

        override var date: Long = 1_000L
        override var autosensResult: AutosensResult? = null
        override var iobData: Array<IobTotal>? = null
        override var currentTemp: CurrentTemp? = null
        override var oapsProfile: OapsProfile? = null
        override var oapsProfileAutoIsf: OapsProfileAutoIsf? = null
        override var mealData: MealData? = null

        override var reason: String = ""
        override var rate: Double = 0.0
        override var percent: Int = 0
        override var duration: Int = 0
        override var smb: Double = 0.0
        override var usePercent: Boolean = false
        override var carbsReq: Int = 0
        override var carbsReqWithin: Int = 0
        override var deliverAt: Long = 0L
        override var targetBG: Double = 0.0
        override var hasPredictions: Boolean = false
        override var variableSens: Double? = null
        override var isfMgdlForCarbs: Double? = null
        override var scriptDebug: List<String>? = null
        override var isTempBasalRequested: Boolean = false
        override var inputConstraints: Constraint<Double>? = null
        override var rateConstraint: Constraint<Double>? = null
        override var percentConstraint: Constraint<Int>? = null
        override var smbConstraint: Constraint<Double>? = null
        override val predictionsAsGv: MutableList<GV> get() = error("not used")
        override val latestPredictionsTime: Long get() = error("not used")
        override val carbsRequiredText: String get() = error("not used")
        override suspend fun isChangeRequested(): Boolean = error("not used")
        override suspend fun resultAsString(): String = error("not used")
        override fun newAndClone(): APSResult = error("not used")
        override fun json(): JsonObject? = error("not used")
        override fun predictions(): Predictions? = error("not used")
    }
}
