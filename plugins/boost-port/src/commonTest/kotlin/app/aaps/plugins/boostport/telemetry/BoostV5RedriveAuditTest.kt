package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5RedriveAuditTest {
    private val audit = BoostV5RedriveAudit

    private fun examine(
        knob: BoostV5ConfigResolutionAudit.Knob = BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP,
        current: Double = 1.0,
        derived: Double = 1.2,
        baseline: Double? = 1.0,
        pending: Double? = null,
        min: Double = 0.25,
        max: Double = 2.5,
        tbr: Double = 1.0,
        sev: Double = 0.1,
    ) = audit.inspect(
        BoostV5RedriveAudit.Input(
            knob, current, derived, baseline, pending, min, max, tbr, sev
        )
    )

    @Test fun firstObservationSeedsBaselineWithoutProposingChange() {
        val result = examine(baseline = null)!!
        assertEquals(BoostV5RedriveAudit.Outcome.BASELINE_RECORDED, result.outcome)
        assertEquals(1.0, result.operativeValue)
        assertEquals(1.2, result.nextDerivedBaseline)
    }

    @Test fun ordinaryRatioMoveKeepsUserRelativeOffset() {
        val result = examine(current = 1.8, baseline = 1.0, derived = 1.2)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, result.outcome)
        assertEquals(2.16, result.operativeValue, 1e-10)
        assertEquals(1.2, result.nextDerivedBaseline!!, 1e-10)
        assertEquals(1.8, result.operativeValue / result.nextDerivedBaseline!!, 1e-10)
    }

    @Test fun jumpsAreClippedToTwentyFivePercentPerEvaluation() {
        val first = examine(derived = 2.0)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, first.outcome)
        assertEquals(1.25, first.operativeValue)
        assertEquals(1.25, first.nextDerivedBaseline)
        val next = examine(
            current = first.operativeValue,
            derived = 2.0,
            baseline = first.nextDerivedBaseline,
        )!!
        assertTrue(next.operativeValue > first.operativeValue)
        assertTrue(next.operativeValue <= first.operativeValue * 1.25 + 0.01)
    }

    @Test fun subNoiseMovementRetainsBaselineForLaterAccumulation() {
        val small = examine(derived = 1.03)!!
        assertEquals(BoostV5RedriveAudit.Outcome.INSIDE_DEADBAND, small.outcome)
        assertEquals(1.0, small.operativeValue)
        assertEquals(1.0, small.nextDerivedBaseline)
        val larger = examine(derived = 1.11)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, larger.outcome)
        assertEquals(1.11, larger.operativeValue, 1e-10)
    }

    @Test fun confirmedCapHasSeparatePointFourSevenNoiseBand() {
        val small = examine(knob = BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, current = 2.5,
            baseline = 2.5, derived = 2.9, min = 1.5, max = 7.5)!!
        assertEquals(BoostV5RedriveAudit.Outcome.INSIDE_DEADBAND, small.outcome)
        val larger = examine(knob = BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, current = 2.5,
            baseline = 2.5, derived = 3.1, min = 1.5, max = 7.5)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, larger.outcome)
    }

    @Test fun tbrGuardSuppressesCapIncreaseButNotReduction() {
        val increase = examine(derived = 1.3, tbr = 6.0)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WITHHELD_CAP_RAISE, increase.outcome)
        assertEquals(1.0, increase.nextDerivedBaseline)
        val decrease = examine(derived = 0.8, tbr = 6.0)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, decrease.outcome)
        assertEquals(0.8, decrease.operativeValue)
    }

    @Test fun severeTbrThresholdAlsoSuppressesIncrease() {
        assertEquals(BoostV5RedriveAudit.Outcome.WITHHELD_CAP_RAISE,
            examine(derived = 1.3, tbr = 3.0, sev = 1.0)!!.outcome)
    }

    @Test fun aggressionOffsetNeedsTwoMatchingDerivations() {
        val first = examine(knob = BoostV5ConfigResolutionAudit.Knob.AGGRESSION, current = 1.0,
            derived = 0.92, baseline = 1.0, min = 0.7, max = 1.3)!!
        assertEquals(BoostV5RedriveAudit.Outcome.AWAITING_CONFIRMATION, first.outcome)
        assertEquals(1.0, first.operativeValue)
        assertEquals(0.92, first.nextPendingValue)
        val second = examine(knob = BoostV5ConfigResolutionAudit.Knob.AGGRESSION, current = 1.0,
            derived = 0.92, baseline = 1.0, pending = first.nextPendingValue,
            min = 0.7, max = 1.3)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, second.outcome)
        assertEquals(0.92, second.operativeValue)
        assertEquals(0.92, second.nextDerivedBaseline)
    }

    @Test fun quantizedFlappingDoesNotReuseOldPendingValue() {
        val first = examine(knob = BoostV5ConfigResolutionAudit.Knob.AGGRESSION, derived = 0.92,
            min = 0.7, max = 1.3)!!
        val noMove = examine(knob = BoostV5ConfigResolutionAudit.Knob.AGGRESSION, derived = 1.0,
            pending = first.nextPendingValue, min = 0.7, max = 1.3)!!
        assertEquals(BoostV5RedriveAudit.Outcome.NO_CHANGE, noMove.outcome)
        assertNull(noMove.nextPendingValue)
    }

    @Test fun raisingHypoCautionCanBeProposedEvenWhenTbrIsHigh() {
        val waiting = examine(knob = BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION, derived = 1.2,
            min = 1.0, max = 2.0, tbr = 8.0)!!
        val after = examine(knob = BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION, derived = 1.2,
            pending = waiting.nextPendingValue, min = 1.0, max = 2.0, tbr = 8.0)!!
        assertEquals(BoostV5RedriveAudit.Outcome.WOULD_UPDATE, after.outcome)
        assertEquals(1.2, after.operativeValue)
    }

    @Test fun unsupportedComputedKnobAndInvalidInputsDoNotProduceProposal() {
        assertNull(examine(knob = BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP))
        assertNull(examine(derived = Double.NaN))
        assertNull(examine(baseline = Double.NEGATIVE_INFINITY))
        assertNull(examine(min = 2.0, max = 1.0))
        assertNull(examine(tbr = 105.0))
        assertNull(examine(pending = Double.NaN))
        assertNotNull(examine())
    }
}
