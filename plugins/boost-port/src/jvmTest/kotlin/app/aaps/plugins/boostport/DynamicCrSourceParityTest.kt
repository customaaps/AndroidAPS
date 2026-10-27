package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.DynamicCarbRatioCalculator
import app.aaps.plugins.boostport.reference.ReferenceDynamicCarbRatioCalculator
import kotlin.random.Random
import kotlin.test.*

class DynamicCrSourceParityTest {
    @Test fun sharedCalculatorMatchesOriginalAcrossFormulasAndTddFallbacks() {
        val actual = DynamicCarbRatioCalculator()
        val source = ReferenceDynamicCarbRatioCalculator()
        val random = Random(20261027)
        repeat(2000) { n ->
            val base = random.nextDouble(3.0, 30.0)
            val bg = random.nextDouble(45.0, 400.0)
            val raw = random.nextDouble(5.0, 150.0)
            val last24 = if (n % 3 == 0) null else random.nextDouble(5.0, 100.0)
            val average7 = if (n % 4 == 0) null else random.nextDouble(5.0, 100.0)
            val weight = random.nextDouble()
            val formula = DynamicCarbRatioCalculator.Formula.entries[n % 2]
            val adjustment = random.nextDouble(0.0, 2.0)
            val peak = random.nextInt(20, 150)
            val custom = n % 3 == 0
            val max = if (n % 11 == 0) 0.7 else 1.2
            val expected = source.computeDynamicCarbRatio(base, bg, 100.0, raw, last24, average7, weight,
                ReferenceDynamicCarbRatioCalculator.Formula.valueOf(formula.name), adjustment, peak, custom, 0.7, max, 50.0)
            val result = actual.computeDynamicCarbRatio(base, bg, 100.0, raw, last24, average7, weight,
                formula, adjustment, peak, custom, 0.7, max, 50.0)
            assertEquals(expected.carbRatio, result.carbRatio)
            assertEquals(expected.reason, result.reason)
            assertTrue(result.carbRatio in base * 0.5..base * 2.0)
        }
    }

    @Test fun sigmoidAnchoringAndInvalidRatioFallbackMatchSource() {
        val calculator = DynamicCarbRatioCalculator()
        val normal = calculator.calculateDynamicCR(10.0, 100.0, 100.0, 1.0,
            DynamicCarbRatioCalculator.Formula.SIGMOID, 40.0, 75, false, 1.0, 0.7, 1.2, 50.0)
        assertEquals(10.0, normal.carbRatio, 1e-9)
        val zero = calculator.calculateDynamicCR(10.0, 180.0, 100.0, 0.0,
            DynamicCarbRatioCalculator.Formula.LOGARITHMIC, 40.0, 75, false, 1.0, 0.7, 1.2, 50.0)
        assertEquals(10.0, zero.carbRatio); assertNotNull(zero.note)
        val degenerate = calculator.calculateDynamicCR(10.0, 180.0, 100.0, 1.0,
            DynamicCarbRatioCalculator.Formula.SIGMOID, 40.0, 75, false, 1.0, 1.0, 1.2, 50.0)
        assertEquals(10.0, degenerate.carbRatio); assertNotNull(degenerate.note)
    }
}
