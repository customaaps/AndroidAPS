package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5ConfigResolutionAuditTest {
    private val audit = BoostV5ConfigResolutionAudit

    private fun suggestions() = mapOf(
        BoostV5ConfigResolutionAudit.Knob.AGGRESSION to 1.0,
        BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION to 1.4,
        BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 4.0,
        BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP to 0.8,
        BoostV5ConfigResolutionAudit.Knob.MAX_IOB to 5.0,
        BoostV5ConfigResolutionAudit.Knob.BOLUS_CAP to 2.0,
        BoostV5ConfigResolutionAudit.Knob.PRIMER_CAP to 0.3,
    )
    private fun inspect(
        stored: Map<BoostV5ConfigResolutionAudit.Knob, Double> = emptyMap(),
        resolved: Set<BoostV5ConfigResolutionAudit.Knob> = emptySet(),
        derived: Map<BoostV5ConfigResolutionAudit.Knob, Double> = suggestions(),
        tbr: Double = 1.0,
        sev: Double = 0.2,
    ) = audit.inspect(stored, resolved, derived, tbr, sev)

    private fun BoostV5ConfigResolutionAudit.Plan.outcome(
        key: BoostV5ConfigResolutionAudit.Knob,
    ) = resolutions.first { it.knob == key }.outcome

    @Test fun untunedFactoryValuesAreEligibleForOfflinePlan() {
        val result = inspect()!!
        assertFalse(result.raiseGuardTripped)
        assertTrue(result.resolutions.all { it.outcome == BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY })
        assertEquals(4.0, result.operative[BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP])
        assertEquals(0.8, result.operative[BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP])
        assertEquals(5.6, result.operative[BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP])
    }

    @Test fun oneUserTunedKeyDoesNotPreventOtherKnobsFromBeingConsidered() {
        val result = inspect(stored = mapOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 5.0))!!
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.KEEP_USER_TUNED, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, result.outcome(BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP))
        assertEquals(5.0, result.operative[BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP])
        assertEquals(6.6, result.operative[BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP])
    }

    @Test fun historicalDefaultsAreNotMistakenForExplicitTuning() {
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, 1.0))
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP, 0.25))
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP, 1.5))
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP, 6.0))
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, null))
        assertTrue(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, 3.7))
        val result = inspect(stored = mapOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 1.0))!!
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
    }

    @Test fun factoryPrecisionToleranceHandlesFloatRoundTrips() {
        assertFalse(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, 2.50005))
        assertTrue(audit.isUserTuned(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, 2.501))
    }

    @Test fun highTbrBlocksOnlyCapIncreasesNotOtherIndependentSettings() {
        val result = inspect(tbr = 5.0)!!
        assertTrue(result.raiseGuardTripped)
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WITHHOLD_CAP_RAISE, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WITHHOLD_CAP_RAISE, result.outcome(BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP))
        assertEquals(2.5, result.operative[BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP])
        assertEquals(0.5, result.operative[BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP])
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, result.outcome(BoostV5ConfigResolutionAudit.Knob.HYPO_CAUTION))
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, result.outcome(BoostV5ConfigResolutionAudit.Knob.PRIMER_CAP))
    }

    @Test fun severeTbrThresholdBlocksRaisesEvenWhenTbr70IsBelowFour() {
        val result = inspect(tbr = 3.0, sev = 1.0)!!
        assertTrue(result.raiseGuardTripped)
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WITHHOLD_CAP_RAISE, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WITHHOLD_CAP_RAISE, result.outcome(BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP))
    }

    @Test fun exactlyFourPercentTbr70DoesNotAloneTripStrictGuard() {
        val result = inspect(tbr = 4.0, sev = 0.9)!!
        assertFalse(result.raiseGuardTripped)
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
    }

    @Test fun loweringACapIsNotHeldByRaiseGuard() {
        val result = inspect(
            stored = mapOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 3.5),
            derived = suggestions() + (BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 2.0),
            tbr = 6.0,
        )!!
        // A user-tuned value always wins; use a factory-default value to
        // exercise the tightening decision without overriding a user's choice.
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.KEEP_USER_TUNED, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        val factory = inspect(
            derived = suggestions() + (BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 2.0), tbr = 6.0
        )!!
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.WOULD_APPLY, factory.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        assertEquals(2.0, factory.operative[BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP])
    }

    @Test fun alreadyResolvedSettingsNeverReceiveANewHypotheticalWrite() {
        val result = inspect(resolved = setOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))!!
        assertEquals(BoostV5ConfigResolutionAudit.Outcome.ALREADY_RESOLVED, result.outcome(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP))
        assertEquals(2.5, result.operative[BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP])
        assertEquals(4.1, result.operative[BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP])
    }

    @Test fun invalidTbrOrMissingDerivationFailsClosed() {
        assertNull(inspect(tbr = Double.NaN))
        assertNull(inspect(tbr = 101.0))
        assertNull(inspect(sev = Double.POSITIVE_INFINITY))
        assertNull(inspect(derived = suggestions() - BoostV5ConfigResolutionAudit.Knob.BOLUS_CAP))
        assertNull(inspect(stored = mapOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to Double.NaN)))
    }

    @Test fun cumulativeIsDerivedFromEffectiveCapsRatherThanSuppliedSuggestion() {
        val result = inspect(
            derived = suggestions() + (BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP to 10.0),
        )!!
        assertEquals(5.6, result.operative[BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP])
        assertEquals(10.0, audit.cumulativeCapSuggestion(9.0, 2.0))
        assertNull(audit.cumulativeCapSuggestion(Double.NaN, 0.5))
        assertNotNull(result.resolutions.firstOrNull { it.knob == BoostV5ConfigResolutionAudit.Knob.CUMULATIVE_SMB_CAP })
    }

    @Test fun legacyVersionTwoReopensFactoryResolvedFlagsButKeepsTuned() {
        val resolved = setOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP, BoostV5ConfigResolutionAudit.Knob.AGGRESSION)
        val stored = mapOf(
            BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP to 1.0,
            BoostV5ConfigResolutionAudit.Knob.COMMITTED_CAP to 0.9,
            BoostV5ConfigResolutionAudit.Knob.AGGRESSION to 1.0,
        )
        val reopened = audit.legacyFactoryFlagsToReopen(resolved, stored, 1)
        assertEquals(setOf(BoostV5ConfigResolutionAudit.Knob.CONFIRMED_CAP, BoostV5ConfigResolutionAudit.Knob.AGGRESSION), reopened)
        assertTrue(audit.legacyFactoryFlagsToReopen(resolved, stored, 2).isEmpty())
    }
}
