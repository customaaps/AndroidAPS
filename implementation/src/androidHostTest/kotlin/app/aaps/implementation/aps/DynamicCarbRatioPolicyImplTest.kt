package app.aaps.implementation.aps

import androidx.collection.LongSparseArray
import app.aaps.core.data.aps.AverageTDD
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.aps.DynamicCarbRatioCalculator
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.Input
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy.TddParts
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.*
import kotlin.test.*

class DynamicCarbRatioPolicyImplTest : TestBase() {
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var tdd: TddCalculator
    private lateinit var policy: DynamicCarbRatioPolicyImpl
    private val input = Input(10.0, 180.0, 100.0, 50.0, 1_790_000_000_000L)
    private val parts = TddParts(40.0, 45.0, 50.0, 8.0, 6.0)

    @BeforeEach fun setup() {
        policy = DynamicCarbRatioPolicyImpl(preferences, tdd)
        whenever(preferences.get(BooleanKey.ApsUseDynamicCarbRatio)).thenReturn(true)
        whenever(preferences.get(DoubleKey.ApsDynamicCrWeightPercentage)).thenReturn(0.65)
        whenever(preferences.get(DoubleKey.ApsDynamicCrAdjustmentFactor)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        whenever(preferences.get(IntKey.ApsDynamicCrPeakTime)).thenReturn(75)
    }

    @Test fun disabledDoesNotReadTddAndRestoresTheProfileCrImmediately() = runTest {
        whenever(preferences.get(BooleanKey.ApsUseDynamicCarbRatio)).thenReturn(false)
        val off = policy.calculate(input)
        assertEquals(10.0, off.carbRatio); assertFalse(off.applied); assertEquals("", off.reason)
        verifyNoInteractions(tdd)
        whenever(preferences.get(BooleanKey.ApsUseDynamicCarbRatio)).thenReturn(true)
        val on = policy.calculate(input, parts)
        assertTrue(on.applied); assertNotEquals(10.0, on.carbRatio)
        whenever(preferences.get(BooleanKey.ApsUseDynamicCarbRatio)).thenReturn(false)
        assertEquals(off, policy.calculate(input))
    }

    @Test fun bothFormulasMatchTheSharedCalculatorAndRetainItsClamps() = runTest {
        val raw = ((1.4 * 8.0 + 0.6 * 6.0) * 3) * 0.33 + 40.0 * 0.34 + 45.0 * 0.33
        for (formula in 0..1) for (bg in listOf(40.0, 70.0, 100.0, 180.0, 400.0)) {
            whenever(preferences.get(IntKey.ApsDynamicCrFormula)).thenReturn(formula)
            val actual = policy.calculate(input.copy(glucose = bg), parts)
            val expected = DynamicCarbRatioCalculator().computeDynamicCarbRatio(10.0, bg, 100.0, raw,
                50.0, 40.0, 0.65, DynamicCarbRatioCalculator.Formula.entries[formula], 1.0, 75,
                false, 0.7, 1.2, 50.0)
            assertEquals(expected.carbRatio, actual.carbRatio)
            assertEquals(expected.reason, actual.reason)
            assertTrue(actual.carbRatio in (10.0 / 1.2)..(10.0 / 0.7))
        }
        verifyNoInteractions(tdd)
    }

    @Test fun missingHistoryAndReadFailureReturnProfileCrWithAnExplicitReason() = runTest {
        val missing = policy.calculate(input, parts.copy(last4Hours = null))
        assertFalse(missing.applied); assertEquals(10.0, missing.carbRatio)
        assertTrue(missing.reason.contains("TDD"))
        whenever(tdd.calculate(input.atMs, 7L, true)).thenThrow(IllegalStateException("unavailable"))
        val failed = policy.calculate(input)
        assertFalse(failed.applied); assertEquals(10.0, failed.carbRatio)
        assertTrue(failed.reason.contains("unavailable"))
    }

    @Test fun partialHistoryUsesTheSourceEightHourFallback() = runTest {
        val result = policy.calculate(input, parts.copy(average7Days = null, average1Day = null, last24Hours = null))
        assertTrue(result.applied); assertTrue(result.carbRatio.isFinite())
        assertTrue(result.reason.contains("weightedTDD=44.4"))
    }

    @Test fun cancellationIsPropagatedInsteadOfBeingConvertedToFallback() = runTest {
        whenever(tdd.calculate(input.atMs, 7L, true)).thenThrow(CancellationException("cancel"))
        assertFailsWith<CancellationException> { policy.calculate(input) }
    }

    @Test fun invalidInputsDoNotTriggerHistoryReads() = runTest {
        for (value in listOf(Double.NaN, 0.0, -5.0, Double.POSITIVE_INFINITY)) {
            val r = policy.calculate(input.copy(glucose = value))
            assertEquals(10.0, r.carbRatio); assertFalse(r.applied)
        }
        verifyNoInteractions(tdd)
    }

    @Test fun allTddQueriesUseTheSameRecordedAnchorAndExpectedWindows() = runTest {
        val seven = LongSparseArray<TDD>()
        val one = LongSparseArray<TDD>()
        whenever(tdd.calculate(input.atMs, 7L, true)).thenReturn(seven)
        whenever(tdd.calculate(input.atMs, 1L, true)).thenReturn(one)
        whenever(tdd.averageTDD(seven)).thenReturn(AverageTDD(TDD(timestamp = input.atMs, utcOffset = 0, totalAmount = 40.0), true))
        whenever(tdd.averageTDD(one)).thenReturn(AverageTDD(TDD(timestamp = input.atMs, utcOffset = 0, totalAmount = 45.0), true))
        whenever(tdd.calculateDaily(input.atMs, -24L, 0L)).thenReturn(TDD(timestamp = input.atMs, utcOffset = 0, totalAmount = 50.0))
        whenever(tdd.calculateDaily(input.atMs, -4L, 0L)).thenReturn(TDD(timestamp = input.atMs, utcOffset = 0, totalAmount = 8.0))
        whenever(tdd.calculateDaily(input.atMs, -8L, -4L)).thenReturn(TDD(timestamp = input.atMs, utcOffset = 0, totalAmount = 6.0))
        assertEquals(policy.calculate(input, parts), policy.calculate(input))
        verify(tdd).calculate(input.atMs, 7L, true); verify(tdd).calculate(input.atMs, 1L, true)
        verify(tdd).calculateDaily(input.atMs, -24L, 0L); verify(tdd).calculateDaily(input.atMs, -4L, 0L)
        verify(tdd).calculateDaily(input.atMs, -8L, -4L)
    }
}
