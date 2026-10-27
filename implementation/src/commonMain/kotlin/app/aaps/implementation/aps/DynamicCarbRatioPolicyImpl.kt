package app.aaps.implementation.aps

import app.aaps.core.interfaces.aps.DynamicCarbRatioCalculator
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.Input
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.Result
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.TddParts
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DynamicCarbRatioPolicyImpl(
    private val preferences: Preferences,
    private val tddCalculator: TddCalculator,
) : DynamicCarbRatioPolicy {
    private val calculator = DynamicCarbRatioCalculator()

    override suspend fun calculate(input: Input, knownTdd: TddParts?): Result {
        if (!preferences.get(BooleanKey.ApsUseDynamicCarbRatio)) return Result(input.baseCr)
        if (!input.baseCr.isFinite() || input.baseCr <= 0.0 || !input.glucose.isFinite() || input.glucose <= 0.0 ||
            !input.target.isFinite() || input.target <= 0.0 || !input.profileIsf.isFinite() || input.profileIsf <= 0.0)
            return Result(input.baseCr, "Dynamic CR: invalid inputs -> using profile CR")
        return try {
            val p = knownTdd ?: readTdd(input.atMs)
            val (average7, average1, last24, last4, last8To4) = p
            val weighted8 = if (last4 != null && last8To4 != null)
                ((1.4 * last4) + (0.6 * last8To4)) * 3 else null
            val raw = when {
                average1 != null && average7 != null && last24 != null && weighted8 != null ->
                    weighted8 * 0.33 + average7 * 0.34 + average1 * 0.33
                else -> weighted8
            }
            if (raw == null || !raw.isFinite() || raw <= 0.0)
                return Result(input.baseCr, "Dynamic CR: no usable TDD -> using profile CR")
            val computed = calculator.computeDynamicCarbRatio(
                baseCR = input.baseCr, currentGlucose = input.glucose, targetGlucose = input.target,
                tddRaw = raw, tddLast24H = p.last24Hours, tdd7D = p.average7Days,
                weightPercentage = preferences.get(DoubleKey.ApsDynamicCrWeightPercentage),
                formula = if (preferences.get(IntKey.ApsDynamicCrFormula) == 1)
                    DynamicCarbRatioCalculator.Formula.SIGMOID else DynamicCarbRatioCalculator.Formula.LOGARITHMIC,
                adjustmentFactor = preferences.get(DoubleKey.ApsDynamicCrAdjustmentFactor),
                insulinPeakTime = preferences.get(IntKey.ApsDynamicCrPeakTime),
                useCustomPeakTime = preferences.get(BooleanKey.ApsDynamicCrUseCustomPeakTime),
                autosensMin = preferences.get(DoubleKey.AutosensMin), autosensMax = preferences.get(DoubleKey.AutosensMax),
                sensitivity = input.profileIsf,
            )
            if (!computed.carbRatio.isFinite() || computed.carbRatio <= 0.0)
                Result(input.baseCr, "Dynamic CR: invalid result -> using profile CR")
            else Result(computed.carbRatio, computed.reason, true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result(input.baseCr, "Dynamic CR: TDD/calculation unavailable -> using profile CR")
        }
    }

    private suspend fun readTdd(atMs: Long) = TddParts(
        tddCalculator.averageTDD(tddCalculator.calculate(atMs, 7L, true))?.data?.totalAmount,
        tddCalculator.averageTDD(tddCalculator.calculate(atMs, 1L, true))?.data?.totalAmount,
        tddCalculator.calculateDaily(atMs, -24L, 0L)?.totalAmount,
        tddCalculator.calculateDaily(atMs, -4L, 0L)?.totalAmount,
        tddCalculator.calculateDaily(atMs, -8L, -4L)?.totalAmount,
    )
}
