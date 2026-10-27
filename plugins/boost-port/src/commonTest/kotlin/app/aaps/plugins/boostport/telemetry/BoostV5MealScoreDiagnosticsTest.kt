package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5MealScoreDiagnosticsTest {
    private val v5 = BoostV5MealScoreDiagnostics

    private fun inputs(
        delta: Double = 10.0,
        acceleration: Double = 15.0,
        ml: Double? = 0.6,
        recentLowBg: Double = 100.0,
        hour: Int = 8,
        exercising: Boolean = false,
        rise: Double = 40.0,
        nullStreak: Int = 0,
    ) = BoostV5MealScoreDiagnostics.Inputs(
        delta, acceleration, ml, recentLowBg, hour, exercising, rise, nullStreak
    )

    @Test fun calibratedWeightsSumToOnePointZeroSeven() {
        assertEquals(1.07, v5.TOTAL_WEIGHT, 1e-12)
        assertEquals(1.07 / 0.87, v5.ML_RENORMALIZATION, 1e-12)
    }

    @Test fun normalizedTermsMatchLegacyMidRange() {
        val result = v5.score(inputs())!!
        assertEquals(0.5, result.terms.delta)
        assertEquals(0.5, result.terms.deltaAcceleration)
        assertEquals(0.6, result.terms.mlMeal)
        assertEquals(1.0, result.terms.notRecentlyLow)
        assertEquals(1.0, result.terms.mealHour)
        assertEquals(1.0, result.terms.notExercising)
        assertEquals(0.5, result.terms.sustainedRise)
        assertFalse(result.renormalizedForMissingMl)
    }

    @Test fun recentLowPenaltyHasSoftenedPointFourFloor() {
        assertEquals(0.4, v5.score(inputs(recentLowBg = 60.0))!!.terms.notRecentlyLow)
        assertEquals(0.4, v5.score(inputs(recentLowBg = 70.0))!!.terms.notRecentlyLow)
        assertEquals(1.0, v5.score(inputs(recentLowBg = 100.0))!!.terms.notRecentlyLow)
    }

    @Test fun slowMealSustainedRiseStartsAtTwentyAndSaturatesAtSixty() {
        assertEquals(0.0, v5.score(inputs(rise = 20.0))!!.terms.sustainedRise)
        assertEquals(0.5, v5.score(inputs(rise = 40.0))!!.terms.sustainedRise)
        assertEquals(1.0, v5.score(inputs(rise = 60.0))!!.terms.sustainedRise)
        assertEquals(1.0, v5.score(inputs(rise = 100.0))!!.terms.sustainedRise)
    }

    @Test fun thresholdForDeltaAndAccelerationSaturates() {
        val terms = v5.score(inputs(delta = 100.0, acceleration = 200.0))!!.terms
        assertEquals(1.0, terms.delta)
        assertEquals(1.0, terms.deltaAcceleration)
    }

    @Test fun exerciseRemovesNotExercisingTerm() {
        val inactive = v5.score(inputs(exercising = false))!!
        val active = v5.score(inputs(exercising = true))!!
        assertEquals(1.0, inactive.terms.notExercising)
        assertEquals(0.0, active.terms.notExercising)
        assertTrue(inactive.score >= active.score)
    }

    @Test fun gaussianMealHoursPeakAtEightThirteenAndNineteen() {
        for (hour in listOf(8, 13, 19)) {
            assertEquals(1.0, v5.score(inputs(hour = hour))!!.terms.mealHour)
        }
        val night = v5.score(inputs(hour = 1))!!.terms.mealHour
        assertTrue(night < 0.01)
    }

    @Test fun missingMlBeforeThreeCyclesIsZeroImputedWithoutScaling() {
        val r = v5.score(inputs(ml = null, nullStreak = 2))!!
        assertEquals(0.0, r.terms.mlMeal)
        assertFalse(r.renormalizedForMissingMl)
    }

    @Test fun thirdMissingMlCycleRestoresTotalWeightCorrectly() {
        val short = v5.score(inputs(ml = null, nullStreak = 2))!!
        val prolonged = v5.score(inputs(ml = null, nullStreak = 3))!!
        assertTrue(prolonged.renormalizedForMissingMl)
        assertEquals(short.score * v5.ML_RENORMALIZATION, prolonged.score, 1e-9)
    }

    @Test fun availableMlNeverTriggersOutageRenormalization() {
        val r = v5.score(inputs(ml = 0.5, nullStreak = 100))!!
        assertFalse(r.renormalizedForMissingMl)
        assertEquals(0.5, r.terms.mlMeal)
    }

    @Test fun scoreIsClippedToUnitInterval() {
        val highest = v5.score(inputs(
            delta = 100.0, acceleration = 100.0, ml = 1.0, rise = 100.0
        ))!!
        assertEquals(1.0, highest.score)
        val lowest = v5.score(inputs(
            delta = -10.0, acceleration = -40.0, ml = 0.0, rise = -20.0,
            recentLowBg = 55.0, hour = 1, exercising = true,
        ))!!
        assertTrue(lowest.score in 0.0..1.0)
    }

    @Test fun malformedClinicalOrClockInputsCauseAbstention() {
        assertNull(v5.score(inputs(delta = Double.NaN)))
        assertNull(v5.score(inputs(ml = Double.POSITIVE_INFINITY)))
        assertNull(v5.score(inputs(ml = 2.0)))
        assertNull(v5.score(inputs(recentLowBg = -1.0)))
        assertNull(v5.score(inputs(hour = 24)))
        assertNull(v5.score(inputs(hour = -1)))
        assertNull(v5.score(inputs(rise = Double.NaN)))
        assertNull(v5.score(inputs(nullStreak = -1)))
    }
}
