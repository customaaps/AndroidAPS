package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.aps.openAPSBoostV5.*
import app.aaps.plugins.boostport.reference.ReferenceV6ReplayOverride
import kotlin.random.Random
import kotlin.test.*

/** Full extracted source seam, original controller and Java/BigDecimal rounding as oracle. */
class BoostV6OverrideSourceParityTest {
    private val context = V5ReplayContext(0.05, true)
    private val settings = V6ReplaySettings()
    private val tranche = settings.copy(confirmTrancheEnabled = true)
    private fun cycle(bg: Double = 150.0, minutes: Double = 0.0): V1ReplayCycle {
        val c = V1ReplayFixture.cycle(bg, 8.0, 6.0, 3.0, 0.5)
        val time = c.time + (minutes * 60_000).toLong()
        return V1ReplayFixture.recorded(c.copy(time = time, glucose = c.glucose.copy(date = time)))
    }
    private fun base(units: Double? = 0.25) = V1ReplayResult(
        runningDynamicIsf = true, units = units, insulinReq = 2.0,
        rate = 1.0, duration = 30, reason = StringBuilder("base; "),
    )
    private val seed by lazy {
        DetermineBasalBoostV5().decide(V1ToV5ReplayInputs.build(cycle(), base(), context, V1ReplayFixture.clock) { null }, V5PersistedState())
    }
    private fun decision(phase: MealHypothesis = MealHypothesis.CONFIRMED, dose: Double = 1.0) = seed.copy(
        finalDose = dose, mealHypothesis = phase, velocityBudgetExempt = false,
        primerBolusU = 0.0, primerUseTempBasal = false, floorWouldAdd = null,
        velocityBudgetWouldAdd = null, primerScaleDebug = "",
    )

    private class Pairing {
        val actual = V6ReplayOverride()
        val source = ReferenceV6ReplayOverride()
    }

    private fun compare(
        c: V1ReplayCycle = cycle(), b: V1ReplayResult = base(),
        d: V5Decision? = decision(), ctx: V5ReplayContext = context,
        options: V6ReplaySettings = settings, pair: Pairing = Pairing(),
    ): V1ReplayResult {
        val beforeReason = b.reason.toString()
        val before = b.copy()
        val expected = pair.source.apply(b, d, c, ctx, options)
        val actual = pair.actual.apply(b, d, c, ctx, options)
        assertEquals(expected.copy(reason = actual.reason), actual)
        assertEquals(expected.reason.toString(), actual.reason.toString())
        assertEquals(pair.source.heldTrancheU(), pair.actual.heldTrancheU())
        assertEquals(before, b, "V1 fields remain unchanged")
        assertEquals(beforeReason, b.reason.toString(), "V1 reason remains unchanged")
        assertNotSame(b, actual)
        assertNotSame(b.reason, actual.reason)
        return actual
    }

    @Test fun sourceBranchMatrixMatchesAllMealStatesAndPermissionGates() {
        for (phase in MealHypothesis.entries) for (active in listOf(false, true))
            for (allowed in listOf(false, true)) for (asleep in listOf(false, true))
                for (recent in listOf(0.15, 0.2)) for (low in listOf(74.9, 75.0)) {
                    val c = cycle().copy(microBolusAllowed = allowed,
                        cumulativeSmbCap60Min = 0.2, recentSmbVolume60Min = recent, recentLowBG45Min = low)
                    val r = compare(c, d = decision(phase), ctx = context.copy(activeMode = active, asleep = asleep))
                    assertTrue(assertNotNull(r.units) in 0.0..1.0)
                }
    }

    @Test fun nonMealAndPostRescueCapsHaveExplicitExpectedOutputs() {
        for (phase in listOf(MealHypothesis.IDLE, MealHypothesis.OBSERVING, MealHypothesis.RECOVERING))
            assertEquals(0.25, compare(d = decision(phase)).units)
        for (phase in listOf(MealHypothesis.CONFIRMED, MealHypothesis.COMMITTED)) {
            assertEquals(1.0, compare(d = decision(phase)).units)
            assertEquals(0.25, compare(c = cycle().copy(recentLowBG45Min = 74.9), d = decision(phase)).units)
            assertEquals(1.0, compare(c = cycle().copy(recentLowBG45Min = 75.0), d = decision(phase)).units)
        }
        assertEquals(0.0, compare(b = base(null), d = decision(MealHypothesis.IDLE)).units)
        assertEquals(0.0, compare(d = decision(dose = 0.0)).units)
    }

    @Test fun cumulativeThresholdPrecedesSleepAndUsesSourcePriorVolumeSemantics() {
        val c = cycle().copy(cumulativeSmbCap60Min = 0.2, recentSmbVolume60Min = 0.2)
        val blocked = compare(c = c, ctx = context.copy(asleep = true))
        assertEquals(0.0, blocked.units)
        assertTrue(blocked.reason.contains("cumulative SMB cap"))
        assertEquals(1.0, compare(c = c.copy(recentSmbVolume60Min = 0.15)).units,
            "source does not clamp this SMB to the remaining 0.05")
        assertEquals(1.0, compare(c = c.copy(cumulativeSmbCap60Min = 0.0)).units)
        assertEquals(0.25, compare(ctx = context.copy(asleep = true)).units)
        assertEquals(0.25, compare(ctx = context.copy(activeMode = false)).units)
        assertEquals(0.25, compare(c = cycle().copy(microBolusAllowed = false)).units)
    }

    @Test fun sourceFinalOverrideUsesSelectedModeRatherThanTheProfileBoostWindowFlag() {
        val c = cycle().let { it.copy(profile = it.profile.copy(boostActive = false)) }
        assertEquals(1.0, compare(c = c).units)
        assertEquals(0.25, compare(c = c, ctx = context.copy(activeMode = false)).units)
    }

    @Test fun velocityAndBolusPrimerExemptionsRemainBoundByPostRescueCap() {
        val d = decision(MealHypothesis.OBSERVING, 0.8)
        for (exempt in listOf(d.copy(velocityBudgetExempt = true), d.copy(primerBolusU = 0.2))) {
            assertEquals(0.8, compare(d = exempt).units)
            assertEquals(0.25, compare(c = cycle().copy(recentLowBG45Min = 70.0), d = exempt).units)
        }
        assertEquals(0.25, compare(d = d.copy(primerBolusU = 0.2, primerUseTempBasal = true)).units)
    }

    @Test fun unavailableV5CapsAmplifiedV1WithoutRaisingSmallOrMissingSmb() {
        val c = cycle().let { it.copy(profile = it.profile.copy(maxUAMSMBBasalMinutes = 15, maxSMBBasalMinutes = 30)) }
        // Original oref fallback rounds the basal-minute limit to 0.1U first: 0.25 -> 0.3.
        assertEquals(0.3, compare(c, base(2.0), d = null).units)
        assertEquals(0.5, compare(c.copy(iob = V1ReplayFixture.iob(-0.3)), base(2.0), d = null).units)
        assertEquals(0.1, compare(c, base(0.1), d = null).units)
        assertNull(compare(c, base(null), d = null).units)
        assertEquals(2.0, compare(c, base(2.0), d = null, ctx = context.copy(activeMode = false)).units)
    }

    @Test fun stockOrefFallbackMatchesSourceAcrossRoundingAndInvalidParameters() {
        val actual = V6ReplayOverride()
        val source = ReferenceV6ReplayOverride()
        val random = Random(261027)
        repeat(1000) {
            val req = random.nextDouble(0.0, 5.0)
            val iob = random.nextDouble(-1.0, 5.0)
            val basal = random.nextDouble(0.05, 4.0)
            val uam = random.nextInt(0, 61)
            val smb = random.nextInt(0, 61)
            val percent = random.nextDouble(1.0, 100.0)
            val increment = listOf(0.01, 0.05, 0.1)[it % 3]
            assertEquals(source.baseOrefSmb(req, iob, basal, uam, smb, percent, increment),
                actual.baseOrefSmb(req, iob, basal, uam, smb, percent, increment))
        }
        assertEquals(0.1, actual.baseOrefSmb(2.0, 0.0, 0.1, 30, 30, 100.0, 0.01))
        assertEquals(0.0, actual.baseOrefSmb(2.0, 0.0, Math.nextDown(0.1), 30, 30, 100.0, 0.01))
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertEquals(0.0, actual.baseOrefSmb(v, 0.0, 1.0, 30, 30, 50.0, 0.05))
            assertEquals(source.baseOrefSmb(2.0, 0.0, 1.0, 30, 30, 50.0, v),
                actual.baseOrefSmb(2.0, 0.0, 1.0, 30, 30, 50.0, v))
        }
    }

    @Test fun primerPreservesExistingProtectiveTempsAndDoesNotExtendSubsumingTemps() {
        val d = decision(MealHypothesis.OBSERVING, 0.0).copy(primerBolusU = 0.3, primerUseTempBasal = true)
        for (rate in listOf(0.0, 0.3)) {
            val r = compare(b = base().copy(rate = rate, duration = 20), d = d)
            assertEquals(rate, r.rate)
            assertEquals(20, r.duration)
            val unchanged = compare(c = cycle().copy(currentTemp = CurrentTemp(20, rate, 0)),
                b = base().copy(rate = null, duration = null), d = d)
            assertNull(unchanged.rate)
            assertNull(unchanged.duration)
            assertTrue(unchanged.reason.contains("tbr-skipped"))
        }
        val high = compare(c = cycle().copy(currentTemp = CurrentTemp(15, 2.4, 0)),
            b = base().copy(rate = null, duration = null), d = d)
        assertNull(high.rate)
        assertNull(high.duration)
        assertTrue(high.reason.contains("tbr-subsumed"))
        val proposed = compare(b = base().copy(rate = 2.4, duration = 15), d = d)
        assertEquals(2.4, proposed.rate)
        assertEquals(15, proposed.duration)
    }

    @Test fun primerOnlyRaisesTempAndRetainsTheSourceDurationRule() {
        val d = decision(MealHypothesis.OBSERVING, 0.0).copy(primerBolusU = 0.3, primerUseTempBasal = true)
        val r = compare(b = base().copy(rate = null, duration = null), d = d)
        assertEquals(1.6, r.rate)
        assertEquals(30, r.duration)
        val longer = compare(b = base().copy(duration = 40), d = d)
        assertEquals(1.6, longer.rate)
        assertEquals(40, longer.duration)
        // Compatibility characterization: retaining 40 minutes means the incremental
        // temp amount is not a strict 0.3U bound before downstream app constraints.
        assertTrue((longer.rate!! - 1.0) * longer.duration!! / 60 > d.primerBolusU)
    }

    @Test fun primerAndFloorDiagnosticsMatchSourceIncludingUserOverride() {
        val d = decision(MealHypothesis.OBSERVING, 0.8).copy(primerBolusU = 0.2,
            floorWouldAdd = 0.1, velocityBudgetWouldAdd = 0.15, primerScaleDebug = "d=8,fR=1,fB=1,fI=1,tgt=0.2")
        val r = compare(d = d, ctx = context.copy(primerTbrFallback = true, primerBolusMode = true,
            composedFloorRequested = true, velocityBudgetRequested = true))
        assertTrue(r.reason.contains("bolus-USER-OVERRIDE"))
        assertTrue(r.reason.contains("brake-floor applied"))
        assertTrue(r.reason.contains("velocity-budget floor applied"))
        assertTrue(compare(d = d.copy(primerBolusU = 0.0)).reason.contains("ROUNDED_TO_ZERO"))
    }

    @Test fun trancheWithholdsThenReleasesExactlyOnceWithinTheSourceWindow() {
        val pair = Pairing()
        assertEquals(0.5, compare(options = tranche, pair = pair).units)
        assertEquals(0.5, pair.actual.heldTrancheU())
        val committed = decision(MealHypothesis.COMMITTED, 0.2)
        assertEquals(0.2, compare(cycle(180.0, 5.0), d = committed, options = tranche, pair = pair).units)
        assertEquals(0.7, compare(cycle(220.0, 10.0), d = committed, options = tranche, pair = pair).units)
        assertEquals(0.0, pair.actual.heldTrancheU())
        assertEquals(0.2, compare(cycle(240.0, 15.0), d = committed, options = tranche, pair = pair).units)
    }

    @Test fun trancheReleaseRespectsConfirmedCapAndIobHeadroom() {
        for ((maxIob, iob, before, cap, expected) in listOf(
            listOf(10.0, 0.5, 0.2, 0.6, 0.6),
            listOf(1.0, 0.9, 0.05, 1.0, 0.1),
        )) {
            val pair = Pairing()
            compare(options = tranche, pair = pair)
            val c = cycle(220.0, 10.0).let { it.copy(iob = V1ReplayFixture.iob(iob),
                profile = it.profile.copy(max_iob = maxIob)) }
            val r = compare(c, d = decision(MealHypothesis.COMMITTED, before),
                ctx = context.copy(confirmedCapU = cap), options = tranche, pair = pair)
            assertEquals(expected, assertNotNull(r.units), 1e-12)
            assertTrue(r.reason.contains("trancheGate=clamped"))
            assertEquals(0.0, pair.actual.heldTrancheU())
        }
    }

    @Test fun trancheDropsForStateHardGateOrRescueBeforeAnyRelease() {
        val committed = decision(MealHypothesis.COMMITTED, 0.2)
        for ((d, low, tag) in listOf(
            Triple(decision(MealHypothesis.IDLE, 0.2), 999.0, "state"),
            Triple(committed.copy(phase3 = committed.phase3.copy(reductions = committed.phase3.reductions.copy(hardGateFired = "test"))), 999.0, "hard-gate"),
            Triple(committed, 70.0, "post-rescue"),
        )) {
            val pair = Pairing()
            compare(options = tranche, pair = pair)
            val r = compare(cycle(220.0, 10.0).copy(recentLowBG45Min = low), d = d, options = tranche, pair = pair)
            assertEquals(0.2, r.units)
            assertTrue(r.reason.contains("trancheGate=dropped:$tag"))
            assertEquals(0.0, pair.actual.heldTrancheU())
        }
    }

    @Test fun skippedOverrideOrDisabledTrancheInvalidatesTheHoldImmediately() {
        for (gate in listOf("inactive", "permission", "sleep", "cap", "unavailable", "disabled")) {
            val pair = Pairing()
            compare(options = tranche, pair = pair)
            val c = cycle(220.0, 10.0).copy(microBolusAllowed = gate != "permission",
                cumulativeSmbCap60Min = if (gate == "cap") 0.2 else 0.0,
                recentSmbVolume60Min = if (gate == "cap") 0.2 else 0.0)
            val r = compare(c, d = if (gate == "unavailable") null else decision(MealHypothesis.COMMITTED, 0.2),
                ctx = context.copy(activeMode = gate != "inactive", asleep = gate == "sleep"),
                options = if (gate == "disabled") settings else tranche, pair = pair)
            assertTrue(r.reason.contains("trancheGate=dropped:seam-skipped"), gate)
            assertEquals(0.0, pair.actual.heldTrancheU(), gate)
        }
    }

    @Test fun trancheSlackExpiryReplacementAndRestartMatchSource() {
        val committed = decision(MealHypothesis.COMMITTED, 0.2)
        val pair = Pairing()
        compare(options = tranche, pair = pair)
        assertEquals(0.2, compare(cycle(220.0, 7.49), d = committed, options = tranche, pair = pair).units)
        assertEquals(0.7, compare(cycle(220.0, 7.5), d = committed, options = tranche, pair = pair).units)
        compare(options = tranche, pair = pair)
        assertEquals(0.2, compare(cycle(220.0, 30.01), d = committed, options = tranche, pair = pair).units)
        assertEquals(0.0, pair.actual.heldTrancheU())
        compare(options = tranche, pair = pair)
        compare(cycle(180.0, 5.0), d = decision(dose = 0.4), options = tranche, pair = pair)
        assertEquals(0.2, pair.actual.heldTrancheU(), "a fresh confirm replaces the old hold")
        assertEquals(0.0, Pairing().actual.heldTrancheU(), "source pending tranche is not persisted")
    }

    @Test fun fullRunnerUsesStockFallbackAndPreservesStateOnV5CalculationFailure() {
        val c = V1ReplayFixture.recorded(V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5))
        val prior = V5PersistedState(mealHypothesis = MealHypothesisState(committedInSession = true))
        val replay = BoostV1V5Replay(V1ReplayFixture.presentation, V1ReplayFixture.clock,
            decideV5 = { _, _ -> error("recorded calculation failure") })
        val frame = replay.run(c, context, prior)
        assertNull(frame.v5)
        assertEquals("IllegalStateException", frame.v5Failure)
        assertSame(prior, frame.nextV5State)
        assertTrue(assertNotNull(frame.v1.units) > 0.5)
        assertEquals(0.5, frame.finalCandidate.units)
        val source = ReferenceV6ReplayOverride().apply(frame.v1, null, c, context, settings)
        assertEquals(source.reason.toString(), frame.finalCandidate.reason.toString())
        assertEquals(source.copy(reason = frame.finalCandidate.reason), frame.finalCandidate)
    }
}
