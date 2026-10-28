package app.aaps.pump.apex

import app.aaps.core.data.pump.defs.Capability
import app.aaps.core.data.pump.defs.PumpCapability
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.pump.apex.ApexDriverLogic.CallbackKind
import app.aaps.pump.apex.ApexDriverLogic.HandshakeAction
import app.aaps.pump.apex.ApexDriverLogic.PasswordReply
import app.aaps.pump.apex.connectivity.ApexBluetooth
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs

/**
 * The eight driver fixes: each test is a case that used to be wrong, or a nearby case
 * that must not become wrong while fixing it.
 */
class ApexDriverLogicTest {

    private val now = 1_786_550_400_000L

    private fun rates(value: Double) = List(48) { value }

    private fun pumpTbr(
        rate: Double? = 1.0,
        percentage: Int? = null,
        isAbsolute: Boolean = true,
        durationMinutes: Int = 30,
        elapsedMinutes: Int = 0,
    ) = ApexPump.TBR(rate, percentage, isAbsolute, durationMinutes, elapsedMinutes)

    private fun expectedTbr(
        rate: Double = 1.0,
        isAbsolute: Boolean = true,
        timestamp: Long = now,
        durationMs: Long = 30 * 60_000L,
    ) = PumpSync.PumpState.TemporaryBasal(
        timestamp = timestamp,
        duration = durationMs,
        rate = rate,
        isAbsolute = isAbsolute,
        type = PumpSync.TemporaryBasalType.NORMAL,
        id = 1L,
        pumpId = timestamp,
    )

    // --- 1. 30-minute basal blocks ---

    @Test
    fun apexPumpTypeAllows30MinuteBasalBlocks() {
        assertThat(PumpCapability.ApexCapabilities.hasCapability(Capability.BasalRate30min)).isTrue()
        assertThat(PumpDescription().fillFor(PumpType.APEX_TRUCARE_III).is30minBasalRatesCapable).isTrue()
    }

    // --- 2. Scan matches the typed serial only ---

    @Test
    fun scanAcceptsOnlyTheExactApexNameForTheTypedSerial() {
        assertThat(ApexBluetooth.isTargetApexDevice("APEX12345678", "12345678")).isTrue()
    }

    @Test
    fun scanRejectsAnotherApexPumpAPrefixMatchAndAMissingName() {
        assertThat(ApexBluetooth.isTargetApexDevice("APEX87654321", "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("APEX12345678extra", "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("APEX1234567", "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("apex12345678", "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("APEX", "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice(null, "12345678")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("APEX12345678", "")).isFalse()
        assertThat(ApexBluetooth.isTargetApexDevice("APEX12345678", "1234567")).isFalse()
    }

    // --- 3. Basal profile cache comparison ---

    @Test
    fun basalProfileMatchesAnIdentical48SlotList() {
        assertThat(ApexDriverLogic.basalProfileMatches(rates(0.6), rates(0.6))).isTrue()
    }

    @Test
    fun basalProfileAllowsHalfAStepAndRejectsMore() {
        // 0.5 U uses the 0.025 step, half-step = 0.0125
        val requested = rates(0.5)
        assertThat(ApexDriverLogic.basalProfileMatches(requested, rates(0.51))).isTrue()
        assertThat(ApexDriverLogic.basalProfileMatches(requested, rates(0.52))).isFalse()
        // 1.0 U uses the 0.05 step, half-step = 0.025
        assertThat(ApexDriverLogic.basalProfileMatches(rates(1.0), rates(1.02))).isTrue()
        assertThat(ApexDriverLogic.basalProfileMatches(rates(1.0), rates(1.03))).isFalse()
    }

    @Test
    fun basalProfileRejectsTheWrongLengthAndASingleDifferentSlot() {
        assertThat(ApexDriverLogic.basalProfileMatches(rates(0.6), List(47) { 0.6 })).isFalse()
        assertThat(ApexDriverLogic.basalProfileMatches(emptyList(), emptyList())).isFalse()
        val pump = rates(0.6).toMutableList()
        pump[17] = 0.9
        assertThat(ApexDriverLogic.basalProfileMatches(rates(0.6), pump)).isFalse()
    }

    // --- 4. TBR reconcile ---

    @Test
    fun tbrDoesNothingWhenNeitherSideHasATemp() {
        assertThat(ApexDriverLogic.tbrSyncAction(null, null, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStartsAKeypadTempThatAapsDoesNotKnow() {
        val action = ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.25, durationMinutes = 45), null, now)
        assertThat(action).isEqualTo(
            ApexDriverLogic.TbrSyncAction.Start(
                timestamp = now,
                durationMs = 45 * 60_000L,
                rate = 1.25,
                isAbsolute = true,
            )
        )
    }

    @Test
    fun tbrReconstructsTheStartTimeFromElapsedMinutes() {
        val action = ApexDriverLogic.tbrSyncAction(
            pumpTbr(rate = 0.5, durationMinutes = 30, elapsedMinutes = 10),
            null,
            now,
        )
        assertThat(action).isEqualTo(
            ApexDriverLogic.TbrSyncAction.Start(
                timestamp = now - 10 * 60_000L,
                durationMs = 30 * 60_000L,
                rate = 0.5,
                isAbsolute = true,
            )
        )
    }

    @Test
    fun tbrLeavesAnAapsStartedTempAloneWhenThePumpStillShowsIt() {
        // AAPS wrote the TBR a few seconds ago; the pump still reports elapsed 0.
        val expected = expectedTbr(rate = 1.0, timestamp = now - 2_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.0, durationMinutes = 30), expected, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStillMatchesAfterAMinuteOfElapsedTime() {
        val expected = expectedTbr(rate = 1.0, timestamp = now - 60_000L, durationMs = 30 * 60_000L)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 1),
                expected,
                now,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStopsWhenThePumpEndedItAndAapsStillThinksItRuns() {
        val expected = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(null, expected, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.Stop)
    }

    @Test
    fun tbrDoesNotStopATempThePumpHasNotReportedYet() {
        // The driver set this TBR five seconds ago and the pump accepted it. A frame that still says
        // "no TBR" is a stale frame, not a cancel: stopping here would drop a TBR the pump is running.
        val justWritten = expectedTbr(rate = 1.0, timestamp = now - 5_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(null, justWritten, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrDoesNotOverwriteAFreshTempWithTheOldRateFromTheSameStaleFrame() {
        val justWritten = expectedTbr(rate = 1.0, timestamp = now - 5_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 2.0, durationMinutes = 30), justWritten, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStopsOnceThePumpHasHadTimeToReportTheTemp() {
        val settled = expectedTbr(rate = 1.0, timestamp = now - 61_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(null, settled, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.Stop)
    }

    @Test
    fun tbrStillPicksUpAKeypadTempWhileAapsHasNoRowAtAll() {
        // The grace above must not delay this: there is no AAPS row to protect.
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.0, durationMinutes = 30), null, now))
            .isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrDoesNotResurrectACancelledAapsTempFromAStalePumpFrame() {
        // AAPS stopped the row. The next V1 still shows the same TBR (elapsed 5 of 30).
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L),
            now,
        )
        assertThat(cancelled.end).isEqualTo(now)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 5),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrDoesNotResurrectAJustSetThenCancelledTempWithElapsedZero() {
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = now - 5_000L, durationMs = 30 * 60_000L),
            now,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrDroppedAtCutsThePlannedEndToTheDropTime() {
        val running = expectedTbr(rate = 1.0, timestamp = now - 90_000L, durationMs = 30 * 60_000L)
        val dropped = ApexDriverLogic.tbrDroppedAt(running, now)
        assertThat(dropped.end).isEqualTo(now)
        assertThat(dropped.timestamp).isEqualTo(running.timestamp)
        assertThat(dropped.duration).isEqualTo(90_000L)
    }

    @Test
    fun tbrDroppedAtDoesNotExtendAnAlreadyClippedRow() {
        val dropped = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(timestamp = now - 90_000L, durationMs = 30 * 60_000L),
            now,
        )
        val later = ApexDriverLogic.tbrDroppedAt(dropped, now + 60_000L)
        assertThat(later.end).isEqualTo(now)
        assertThat(later.duration).isEqualTo(90_000L)
    }

    @Test
    fun lastAapsFromPumpTbrClipsToTheDropTime() {
        val dropped = ApexDriverLogic.lastAapsFromPumpTbr(
            pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 5),
            now,
        )
        assertThat(dropped).isNotNull()
        assertThat(dropped!!.end).isEqualTo(now)
        assertThat(dropped.timestamp).isEqualTo(now - 5 * 60_000L)
        assertThat(dropped.rate).isEqualTo(1.0)
    }

    @Test
    fun lastAapsFromPumpTbrIsNullWhenThePumpHasNoRunningTemp() {
        assertThat(ApexDriverLogic.lastAapsFromPumpTbr(null, now)).isNull()
        assertThat(
            ApexDriverLogic.lastAapsFromPumpTbr(
                pumpTbr(rate = 1.0, durationMinutes = 15, elapsedMinutes = 15),
                now,
            )
        ).isNull()
    }

    @Test
    fun tbrDoesNotDuplicateAJustWrittenTempWhenSqlHasNotCaughtUp() {
        // Pump accepted a TBR. SQL insert succeeded but expectedPumpState is still null.
        // lastAaps still has the planned end — that must not Start a second row.
        val written = expectedTbr(rate = 1.0, timestamp = now - 2_000L, durationMs = 30 * 60_000L)
        assertThat(written.end).isGreaterThan(now)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = written,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStartsWhenLastAapsWasClearedAfterAFailedWrite() {
        // Cancel-then-set at the same rate, SQL write failed: lastAaps must be cleared so Start retries.
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = null,
            )
        ).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrKeypadRestartWithInconsistentElapsedStartsEvenDuringGrace() {
        // Old episode started 100 s ago, cancelled 10 s ago. A same-rate frame with elapsed 0
        // cannot be a leftover of it (a leftover keeps counting from the old start), so it is a
        // new keypad TBR and must start right away instead of hiding for the whole end grace.
        val cancelAt = now - 10_000L
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = cancelAt - 90_000L, durationMs = 30 * 60_000L),
            cancelAt,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrLeftoverFrameWithConsistentElapsedIsIgnoredDuringGrace() {
        // Same situation, but the frame's elapsed keeps counting from the OLD start (the pump has
        // not applied the cancel in that frame's view) - a leftover, not a new keypad TBR.
        val cancelAt = now - 10_000L
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = cancelAt - 150_000L, durationMs = 30 * 60_000L),
            cancelAt,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 2),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrSameRateKeypadStartsOnceClippedCancelGraceEnds() {
        // Cancel 90 s into a 30 min TBR. A keypad TBR that started at cancel time is a new episode
        // once the 3 min grace after the actual drop has passed.
        val cancelAt = now - ApexDriverLogic.TBR_END_GRACE_MS - 1L
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = cancelAt - 90_000L, durationMs = 30 * 60_000L),
            cancelAt,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 3),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrDoesNotRestartAnAapsTempTheSqlRowJustDropped() {
        // SQL active-at requires timestamp+duration > now, so at the exact end the row is gone.
        // The pump still reports 1 minute left. Starting that as a new full 30 min TBR would cut
        // the old history row.
        val ended = expectedTbr(rate = 1.0, timestamp = now - 30 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ended.end).isEqualTo(now)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 29),
                expected = null,
                nowMillis = now,
                lastAapsTbr = ended,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStillStartsAKeypadTempWhenLastAapsWasADifferentRate() {
        val last = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        val action = ApexDriverLogic.tbrSyncAction(
            pumpTbr(rate = 2.0, durationMinutes = 30, elapsedMinutes = 0),
            expected = null,
            nowMillis = now,
            lastAapsTbr = last,
        )
        assertThat(action).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
        assertThat((action as ApexDriverLogic.TbrSyncAction.Start).rate).isEqualTo(2.0)
    }

    @Test
    fun tbrStillStartsAKeypadTempWhenLastAapsEndedLongAgo() {
        val old = expectedTbr(rate = 1.0, timestamp = now - 2 * 60 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = old,
            )
        ).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    // --- Insulin concentration: the database stores absolute rates as pump rate x concentration ---

    @Test
    fun tbrMatchesTheDbRowForAConcentrationOtherThanOne() {
        // Pump runs 2.0 U/h in pump units; with U200 insulin the database row stores 4.0.
        // Comparing raw pump units against the row made every own TBR look foreign and
        // re-started it each cycle.
        val aaps = expectedTbr(rate = 4.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 2.0, durationMinutes = 30, elapsedMinutes = 5),
                expected = aaps,
                nowMillis = now,
                insulinConcentration = 2.0,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStartKeepsPumpUnitsForThePumpSyncWrite() {
        // Start.rate feeds PumpRate(), which converts to database units on the write path -
        // the action itself must stay in pump units regardless of the concentration.
        val action = ApexDriverLogic.tbrSyncAction(
            pumpTbr(rate = 2.0, durationMinutes = 30, elapsedMinutes = 0),
            expected = null,
            nowMillis = now,
            insulinConcentration = 2.0,
        )
        assertThat(action).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
        assertThat((action as ApexDriverLogic.TbrSyncAction.Start).rate).isEqualTo(2.0)
    }

    @Test
    fun tbrSameEpisodeComparesRatesInDbUnits() {
        // The just-cancelled row holds 4.0 (db units); the leftover frame reports 2.0 pump units.
        // With the concentration applied they are the same episode - without it the leftover
        // would be started again as a new TBR right after the cancel.
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 4.0, timestamp = now - 150_000L, durationMs = 30 * 60_000L),
            now - 10_000L,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 2.0, durationMinutes = 30, elapsedMinutes = 2),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
                insulinConcentration = 2.0,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStillStartsANewKeypadTempAfterCancellingAnOlderAapsTemp() {
        // Cancelled 20 min into a 30 min TBR. A new keypad TBR at elapsed 0 starts now, not 20 min ago.
        val cancelled = ApexDriverLogic.tbrDroppedAt(
            expectedTbr(rate = 1.0, timestamp = now - 20 * 60_000L, durationMs = 30 * 60_000L),
            now,
        )
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 0),
                expected = null,
                nowMillis = now,
                lastAapsTbr = cancelled,
            )
        ).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrCorrectsARowDatedInTheFutureInsteadOfWaitingForIt() {
        // A phone clock that jumped back must not park the reconcile until the row expires.
        val future = expectedTbr(rate = 1.0, timestamp = now + 10 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(null, future, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.Stop)
    }

    @Test
    fun tbrReplacesWhenThePumpRateChanged() {
        val expected = expectedTbr(rate = 1.0, timestamp = now - 60_000L, durationMs = 30 * 60_000L)
        val action = ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 2.0, durationMinutes = 30), expected, now)
        assertThat(action).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
        assertThat((action as ApexDriverLogic.TbrSyncAction.Start).rate).isEqualTo(2.0)
    }

    @Test
    fun tbrDoesNotTreatPercentAsAbsoluteEvenWhenTheNumbersMatch() {
        // Older than the settle grace, so the mismatch is acted on and not waited out.
        val expected = expectedTbr(rate = 2.0, isAbsolute = true, timestamp = now - 5 * 60_000L)
        val pump = pumpTbr(rate = null, percentage = 2, isAbsolute = false, durationMinutes = 30)
        val action = ApexDriverLogic.tbrSyncAction(pump, expected, now)
        assertThat(action).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
        assertThat((action as ApexDriverLogic.TbrSyncAction.Start).isAbsolute).isFalse()
        assertThat(action.rate).isEqualTo(2.0)
    }

    @Test
    fun tbrIgnoresAPumpTempThatHasAlreadyUsedUpItsDuration() {
        val finished = pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 30)
        assertThat(ApexDriverLogic.tbrSyncAction(finished, null, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
        val expected = expectedTbr(rate = 1.0, timestamp = now - 30 * 60_000L, durationMs = 30 * 60_000L)
        // AAPS remaining is also 0, so both sides are idle.
        assertThat(ApexDriverLogic.tbrSyncAction(finished, expected, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrStopsAapsWhenThePumpTempHasExpiredButAapsHasRemainingTime() {
        val finished = pumpTbr(rate = 1.0, durationMinutes = 15, elapsedMinutes = 15)
        val expected = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(finished, expected, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.Stop)
    }

    @Test
    fun tbrRemainingWithinThreeMinutesStillMatchesAndFourDoesNot() {
        val almost = expectedTbr(rate = 1.0, timestamp = now - 3 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.0, durationMinutes = 30), almost, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)

        val drifted = expectedTbr(rate = 1.0, timestamp = now - 4 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.0, durationMinutes = 30), drifted, now))
            .isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrDoesNotDuplicateAnAapsTempDuringItsLastMinute() {
        // The AAPS row ends in 50 s. Counted down, that is "0 minutes left", but the pump still
        // reports one minute left, because it only counts whole elapsed minutes. Reading that as
        // "AAPS has no TBR" inserted a second row, with a new pumpId, near the end of every TBR.
        val expected = expectedTbr(rate = 1.0, timestamp = now - (29 * 60 + 10) * 1_000L, durationMs = 30 * 60_000L)
        assertThat(
            ApexDriverLogic.tbrSyncAction(
                pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 29),
                expected,
                now,
            )
        ).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrRemainingCountsAPartialMinuteAsOne() {
        // Same as the pump: any time left at all is at least one minute, and only a finished TBR is 0.
        fun remaining(msLeft: Long) =
            ApexDriverLogic.expectedRemainingMinutes(expectedTbr(timestamp = now - 30 * 60_000L + msLeft, durationMs = 30 * 60_000L), now)
        assertThat(remaining(-1_000L)).isEqualTo(0)
        assertThat(remaining(0L)).isEqualTo(0)
        assertThat(remaining(1L)).isEqualTo(1)
        assertThat(remaining(59_999L)).isEqualTo(1)
        assertThat(remaining(60_000L)).isEqualTo(1)
        assertThat(remaining(60_001L)).isEqualTo(2)
    }

    @Test
    fun tbrAllowsOneEncodingStepButNotARealRateChange() {
        val expected = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.025, elapsedMinutes = 5), expected, now))
            .isEqualTo(ApexDriverLogic.TbrSyncAction.None)
        assertThat(ApexDriverLogic.tbrSyncAction(pumpTbr(rate = 1.1, elapsedMinutes = 5), expected, now))
            .isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrStartNeverUsesAFutureTimestamp() {
        // Negative elapsed must not push the start into the future.
        val action = ApexDriverLogic.tbrSyncAction(
            pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = -5),
            null,
            now,
        )
        assertThat(action).isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
        assertThat((action as ApexDriverLogic.TbrSyncAction.Start).timestamp).isAtMost(now)
    }

    // --- 5. Status freshness is phone-clock arrival, not pump RTC ---

    @Test
    fun statusIsNotFreshUntilAFrameHasArrived() {
        assertThat(ApexDriverLogic.isStatusFresh(0, now)).isFalse()
    }

    @Test
    fun statusIsFreshInsideTheWindowAndStaleOutsideIt() {
        assertThat(ApexDriverLogic.isStatusFresh(now - 24_999, now)).isTrue()
        assertThat(ApexDriverLogic.isStatusFresh(now - 25_000, now)).isFalse()
        assertThat(ApexDriverLogic.isStatusFresh(now - 25_001, now)).isFalse()
        assertThat(ApexDriverLogic.isStatusFresh(now, now)).isTrue()
    }

    @Test
    fun statusIsNotFreshWhenThePhoneClockWentBackwards() {
        assertThat(ApexDriverLogic.isStatusFresh(now + 5_000, now)).isFalse()
    }

    @Test
    fun aPumpClockTwentySecondsAheadWouldHaveLookedFreshForeverOnTheOldRule() {
        // Old rule: abs(phoneNow - pumpRtc) < 25s. Pump RTC = now+20s always → skip forever.
        val pumpRtcAhead = now + 20_000
        assertThat(abs(now - pumpRtcAhead) < 25_000).isTrue()
        // New rule uses the arrival time, which is in the past, so a later call is not fresh.
        assertThat(ApexDriverLogic.isStatusFresh(lastReceivedAt = now - 60_000, nowMillis = now)).isFalse()
    }

    // --- 6. Serial / configured ---

    @Test
    fun serialMustBeExactlyEightDigits() {
        assertThat(ApexDriverLogic.isSerialConfigured("12345678")).isTrue()
        assertThat(ApexDriverLogic.isSerialConfigured("")).isFalse()
        assertThat(ApexDriverLogic.isSerialConfigured("1234567")).isFalse()
        assertThat(ApexDriverLogic.isSerialConfigured("123456789")).isFalse()
        assertThat(ApexDriverLogic.isSerialConfigured("1234567a")).isFalse()
        assertThat(ApexDriverLogic.isSerialConfigured(" 12345678")).isFalse()
        assertThat(ApexDriverLogic.isSerialConfigured("12345678 ")).isFalse()
    }

    // --- 7. TBR duration 0 must not be sent ---

    @Test
    fun tbrDurationBelowOneStepIsRefusedAndWholeStepsAreKept() {
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(0)).isNull()
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(-15)).isNull()
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(1)).isNull()
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(14)).isNull()
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(15)).isEqualTo(15)
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(16)).isEqualTo(15)
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(29)).isEqualTo(15)
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(30)).isEqualTo(30)
        assertThat(ApexDriverLogic.alignedTbrDurationMinutes(1440)).isEqualTo(1440)
    }

    // --- 8. StatusV1 TBR decode: garbage must not become Start or Stop ---

    private fun decode(
        activeRaw: Int = 1,
        isAbsoluteRaw: Int = 1,
        rateRaw: Int = 40,
        durationMinutes: Int = 30,
        elapsedMinutes: Int = 0,
    ) = ApexDriverLogic.decodeStatusTbr(activeRaw, isAbsoluteRaw, rateRaw, durationMinutes, elapsedMinutes)

    @Test
    fun decodeStatusTbr_inactiveIsNone() {
        assertThat(decode(activeRaw = 0)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.None)
    }

    @Test
    fun decodeStatusTbr_finishedElapsedIsNone() {
        assertThat(decode(durationMinutes = 30, elapsedMinutes = 30)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.None)
    }

    @Test
    fun decodeStatusTbr_runningAbsoluteIsKept() {
        val decoded = decode(rateRaw = 40, durationMinutes = 30, elapsedMinutes = 5)
        val running = decoded as ApexDriverLogic.DecodedStatusTbr.Running
        assertThat(running.tbr.rate).isWithin(1e-9).of(1.0)
        assertThat(running.tbr.isAbsolute).isTrue()
        assertThat(running.tbr.durationMinutes).isEqualTo(30)
        assertThat(running.tbr.elapsedMinutes).isEqualTo(5)
    }

    @Test
    fun decodeStatusTbr_ushortMaxRateIsInvalid() {
        assertThat(decode(rateRaw = ApexDriverLogic.TBR_RATE_SENTINEL)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_overlongDurationIsInvalid() {
        assertThat(decode(durationMinutes = 65535, elapsedMinutes = 0)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_maxDurationBoundaryIsExact() {
        // 1440 min is the longest TBR the pump type advertises: it must decode as Running,
        // and one minute more is garbage. Guards the > vs >= direction of the bound.
        assertThat(decode(durationMinutes = ApexDriverLogic.TBR_MAX_DURATION_MIN, elapsedMinutes = 0))
            .isInstanceOf(ApexDriverLogic.DecodedStatusTbr.Running::class.java)
        assertThat(decode(durationMinutes = ApexDriverLogic.TBR_MAX_DURATION_MIN + 1, elapsedMinutes = 0))
            .isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_elapsedPastDurationIsInvalidNotNone() {
        assertThat(decode(durationMinutes = 30, elapsedMinutes = 31)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_zeroDurationWhileActiveIsInvalid() {
        assertThat(decode(durationMinutes = 0, elapsedMinutes = 0)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_rateAtDeviceCeilingIsKept() {
        val decoded = decode(rateRaw = ApexDriverLogic.TBR_MAX_ABSOLUTE_RAW)
        val running = decoded as ApexDriverLogic.DecodedStatusTbr.Running
        assertThat(running.tbr.rate).isWithin(1e-9).of(ApexDriverLogic.TBR_MAX_ABSOLUTE_U)
    }

    @Test
    fun decodeStatusTbr_rateOneStepAboveDeviceCeilingIsInvalid() {
        assertThat(decode(rateRaw = ApexDriverLogic.TBR_MAX_ABSOLUTE_RAW + 1))
            .isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_rateAboveHardCeilingIsInvalid() {
        // 1600 * 0.025 = 40 U/h, above TBR_MAX_ABSOLUTE_U.
        assertThat(decode(rateRaw = 1600)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_keypadRateIsKeptEvenWhenSameFrameMaxBasalWouldHaveBeenLower() {
        // 200 * 0.025 = 5 U/h. Same-frame maxBasal used to cap this and hide a real keypad TBR.
        val decoded = decode(rateRaw = 200)
        val running = decoded as ApexDriverLogic.DecodedStatusTbr.Running
        assertThat(running.tbr.rate).isWithin(1e-9).of(5.0)
    }

    @Test
    fun decodeStatusTbr_percentAboveCapIsInvalid() {
        assertThat(decode(isAbsoluteRaw = 0, rateRaw = 501)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_nonBooleanActiveIsInvalid() {
        assertThat(decode(activeRaw = 2)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
        assertThat(decode(activeRaw = 255)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_nonBooleanAbsoluteIsInvalidWhenActive() {
        assertThat(decode(activeRaw = 1, isAbsoluteRaw = 2)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
        assertThat(decode(activeRaw = 1, isAbsoluteRaw = 255)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.Invalid)
    }

    @Test
    fun decodeStatusTbr_inactiveIgnoresGarbageAbsoluteFlag() {
        assertThat(decode(activeRaw = 0, isAbsoluteRaw = 255)).isEqualTo(ApexDriverLogic.DecodedStatusTbr.None)
    }

    @Test
    fun resolvedTbr_invalidKeepsPreviousAndDoesNotBecomeNull() {
        val previous = pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 5)
        assertThat(ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid, previous)).isEqualTo(previous)
        assertThat(ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid, null)).isNull()
        assertThat(ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.None, previous)).isNull()
    }

    @Test
    fun shouldReconcileDecodedTbr_skipsInvalidOnly() {
        assertThat(ApexDriverLogic.shouldReconcileDecodedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid)).isFalse()
        assertThat(ApexDriverLogic.shouldReconcileDecodedTbr(ApexDriverLogic.DecodedStatusTbr.None)).isTrue()
        val running = decode() as ApexDriverLogic.DecodedStatusTbr.Running
        assertThat(ApexDriverLogic.shouldReconcileDecodedTbr(running)).isTrue()
    }

    @Test
    fun tbrSyncAction_doesNotStopWhenInvalidDecodeKeepsPreviousPumpTbr() {
        val previous = pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 5)
        val kept = ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid, previous)
        val expected = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.tbrSyncAction(kept, expected, now)).isEqualTo(ApexDriverLogic.TbrSyncAction.None)
    }

    @Test
    fun tbrSyncAction_keptPreviousWithFrozenElapsedWouldStartAfterRemainingSkew() {
        // Why Invalid must not be passed to tbrSyncAction: display keeps elapsed=5, AAPS remaining
        // drifts, then Start writes a new row. shouldReconcileDecodedTbr is the gate.
        val previous = pumpTbr(rate = 1.0, durationMinutes = 30, elapsedMinutes = 5)
        val kept = ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid, previous)
        val expected = expectedTbr(rate = 1.0, timestamp = now - 9 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.shouldReconcileDecodedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid)).isFalse()
        assertThat(ApexDriverLogic.tbrSyncAction(kept, expected, now))
            .isInstanceOf(ApexDriverLogic.TbrSyncAction.Start::class.java)
    }

    @Test
    fun tbrSyncAction_nullPumpTbrStopsSettledExpected() {
        // Why Invalid + no previous must not be passed as pumpTbr=null: that is Stop after settle.
        val expected = expectedTbr(rate = 1.0, timestamp = now - 5 * 60_000L, durationMs = 30 * 60_000L)
        assertThat(ApexDriverLogic.resolvedTbr(ApexDriverLogic.DecodedStatusTbr.Invalid, null)).isNull()
        assertThat(ApexDriverLogic.tbrSyncAction(null, expected, now)).isEqualTo(ApexDriverLogic.TbrSyncAction.Stop)
    }

    // --- TBR tail logging ---

    @Test
    fun shouldLogTbrTail_quietWhenThereIsNoTbrAtAll() {
        assertThat(ApexDriverLogic.shouldLogTbrTail(null, null)).isFalse()
    }

    @Test
    fun shouldLogTbrTail_logsWhenATbrAppearsOrDisappears() {
        // Disappearing is the case this exists for: it is the moment a keypad cancel would show up.
        assertThat(ApexDriverLogic.shouldLogTbrTail(null, pumpTbr())).isTrue()
        assertThat(ApexDriverLogic.shouldLogTbrTail(pumpTbr(), null)).isTrue()
    }

    @Test
    fun shouldLogTbrTail_quietWhileOnlyElapsedTicks() {
        // The noisy case: elapsed moves on almost every frame and must not fill the log.
        val before = pumpTbr(elapsedMinutes = 5)
        val after = pumpTbr(elapsedMinutes = 6)
        assertThat(ApexDriverLogic.shouldLogTbrTail(before, after)).isFalse()
    }

    @Test
    fun shouldLogTbrTail_quietWhileElapsedStandsStill() {
        // The pump counts whole minutes and frames arrive every two, so an unchanged counter on two
        // consecutive frames is ordinary, not a stall. It must stay as quiet as a tick up.
        val before = pumpTbr(elapsedMinutes = 5)
        val after = pumpTbr(elapsedMinutes = 5)
        assertThat(ApexDriverLogic.shouldLogTbrTail(before, after)).isFalse()
    }

    @Test
    fun shouldLogTbrTail_logsWhenElapsedGoesBackwards() {
        // The pump only counts forwards, so a smaller value under a running temp means the counter was
        // reset - which is the whole question about a suspend, a battery change or a cartridge change.
        val before = pumpTbr(elapsedMinutes = 12)
        val after = pumpTbr(elapsedMinutes = 0)
        assertThat(ApexDriverLogic.shouldLogTbrTail(before, after)).isTrue()
    }

    @Test
    fun shouldLogTbrTail_logsOnASingleMinuteBackwards() {
        // No tolerance on the way back: one minute is already impossible on a forward-only counter.
        assertThat(ApexDriverLogic.shouldLogTbrTail(pumpTbr(elapsedMinutes = 8), pumpTbr(elapsedMinutes = 7))).isTrue()
    }

    @Test
    fun shouldLogTbrTail_logsWhenTheTbrItselfChanges() {
        assertThat(ApexDriverLogic.shouldLogTbrTail(pumpTbr(rate = 1.0), pumpTbr(rate = 1.5))).isTrue()
        assertThat(ApexDriverLogic.shouldLogTbrTail(pumpTbr(durationMinutes = 30), pumpTbr(durationMinutes = 60))).isTrue()
        assertThat(
            ApexDriverLogic.shouldLogTbrTail(
                pumpTbr(rate = 1.0, isAbsolute = true),
                pumpTbr(rate = null, percentage = 120, isAbsolute = false)
            )
        ).isTrue()
    }

    // --- refused temporary basal writes ---

    @Test
    fun tbrStartRefusal_namesTheOneCauseTheDriverCanSee() {
        assertThat(ApexDriverLogic.tbrStartRefusal(profileRunning = false))
            .isEqualTo(ApexDriverLogic.TbrSyncRefusal.NoProfile)
    }

    @Test
    fun tbrStartRefusal_isUnknownWhenAProfileWasRunning() {
        // Core refuses for several reasons and does not say which. With a profile running the driver
        // cannot tell them apart, and naming one would print a guess as a fact.
        assertThat(ApexDriverLogic.tbrStartRefusal(profileRunning = true))
            .isEqualTo(ApexDriverLogic.TbrSyncRefusal.Unknown)
    }

    @Test
    fun tbrStopRefusal_saysNothingToCloseWhenNoRowWasOpen() {
        assertThat(ApexDriverLogic.tbrStopRefusal(hadRunningRow = false))
            .isEqualTo(ApexDriverLogic.TbrSyncRefusal.NothingToClose)
    }

    @Test
    fun tbrStopRefusal_isUnknownWhenARowWasOpen() {
        assertThat(ApexDriverLogic.tbrStopRefusal(hadRunningRow = true))
            .isEqualTo(ApexDriverLogic.TbrSyncRefusal.Unknown)
    }

    // --- bolus delivery time ---

    /** Reconnect plus command allowance, the part that does not depend on the dose. */
    private val bolusOverheadMs =
        ApexDriverLogic.BOLUS_RECONNECT_ALLOWANCE_MS + ApexDriverLogic.BOLUS_COMMANDS_ALLOWANCE_MS

    @Test
    fun bolusDeliveryMs_smallDoseUsesTheSlowRate() {
        // 1 U and below is delivered at 0.025 U/s, so 0.5 U is 20 s.
        assertThat(ApexDriverLogic.bolusDeliveryMs(0.5, lowSpeed = false)).isEqualTo(20_000L + bolusOverheadMs)
    }

    @Test
    fun bolusDeliveryMs_oneUnitIsStillTheSlowRate() {
        // The limit itself belongs to the slow rate: 1 U is 40 s, not 20 s.
        assertThat(ApexDriverLogic.bolusDeliveryMs(1.0, lowSpeed = false)).isEqualTo(40_000L + bolusOverheadMs)
    }

    @Test
    fun bolusDeliveryMs_aboveOneUnitUsesTheFastRate() {
        // Above 1 U the pump doubles to 0.05 U/s, so 6.7 U is 134 s - which matches the 6.7 U bolus
        // captured on 2026-08-26, accepted 10:26:35.9 and finished 10:28:50.1, i.e. 134.2 s.
        assertThat(ApexDriverLogic.bolusDeliveryMs(6.7, lowSpeed = false)).isEqualTo(134_000L + bolusOverheadMs)
    }

    @Test
    fun bolusDeliveryMs_lowSpeedIsFiveTimesSlower() {
        assertThat(ApexDriverLogic.bolusDeliveryMs(0.5, lowSpeed = true)).isEqualTo(100_000L + bolusOverheadMs)
    }

    @Test
    fun bolusDeliveryMs_neverBelowTheFixedOverhead() {
        // A zero dose still has to survive a reconnect and three commands, or the wait would end
        // before the pump had a chance to answer at all.
        assertThat(ApexDriverLogic.bolusDeliveryMs(0.0, lowSpeed = false)).isEqualTo(bolusOverheadMs)
    }

    @Test
    fun bolusDeliveryMs_halfSecondStepRoundsToWholeSeconds() {
        // Doses are multiples of 0.025 U, so above the limit the raw value can land on a half second:
        // 1.175 U / 0.05 = 23.5 s. Rounded, as the call site did before this moved into the logic.
        assertThat(ApexDriverLogic.bolusDeliveryMs(1.175, lowSpeed = false)).isEqualTo(24_000L + bolusOverheadMs)
    }

    /** The pump's own dose grid, and so the tolerance a full delivery has to land inside. */
    private val bolusStep = 0.025

    @Test
    fun isBolusShortfall_aFullyDeliveredBolusIsNotAShortfall() {
        assertThat(ApexDriverLogic.isBolusShortfall(2.0, 2.0, cancelled = false, provenFailure = false, bolusStep = bolusStep)).isFalse()
    }

    @Test
    fun isBolusShortfall_roundingOntoThePumpGridIsNotAShortfall() {
        // A request that is not on the 0.025 U grid is delivered rounded, so the pump reports up to
        // half a step less. That is a complete bolus, not a short one.
        assertThat(ApexDriverLogic.isBolusShortfall(0.13, 0.125, cancelled = false, provenFailure = false, bolusStep = bolusStep)).isFalse()
    }

    @Test
    fun isBolusShortfall_aHalfDeliveredBolusIsAShortfall() {
        // The case that used to be reported as a plain success: the link dropped mid-bolus, the wait
        // ran out, and `failed` was never set because something had been delivered.
        assertThat(ApexDriverLogic.isBolusShortfall(2.0, 0.75, cancelled = false, provenFailure = false, bolusStep = bolusStep)).isTrue()
    }

    @Test
    fun isBolusShortfall_nothingConfirmedAtAllIsAShortfall() {
        // No progress frame ever arrived. Not proof that nothing went in - which is exactly why the
        // user has to be told rather than the loop told to try again.
        assertThat(ApexDriverLogic.isBolusShortfall(1.0, 0.0, cancelled = false, provenFailure = false, bolusStep = bolusStep)).isTrue()
    }

    @Test
    fun isBolusShortfall_aCancelledBolusIsNotAShortfall() {
        // Less insulin on purpose, and the pump reported how much.
        assertThat(ApexDriverLogic.isBolusShortfall(2.0, 0.75, cancelled = true, provenFailure = false, bolusStep = bolusStep)).isFalse()
    }

    @Test
    fun isBolusShortfall_aProvenFailureIsNotAShortfall() {
        // The pump proved the motor stopped, so nothing uncertain is left to say.
        assertThat(ApexDriverLogic.isBolusShortfall(2.0, 0.0, cancelled = false, provenFailure = true, bolusStep = bolusStep)).isFalse()
    }

    /**
     * The ending the card exists for. The bolus was ended by an alarm that says nothing about the
     * motor, so `InProgressBolus.failed` is set while nothing proves the insulin did not go in - and
     * the driver keeps the pending sync on this very ending for that reason. Keying the card on
     * `failed` alone hid the card here, which is the one place it is most needed.
     */
    @Test
    fun isBolusShortfall_anUnprovenFailureIsStillAShortfall() {
        assertThat(ApexDriverLogic.isBolusShortfall(2.0, 0.0, cancelled = false, provenFailure = false, bolusStep = bolusStep)).isTrue()
    }

    /**
     * The boundary belongs to the shortfall: one whole step is more than pump rounding can explain.
     *
     * Every pair here is exactly one 0.025 U step apart, and they are chosen to sit on both sides of
     * the binary representation: 1.0 - 0.975 evaluates just above 0.025, while 2.0 - 1.975 and
     * 0.15 - 0.125 evaluate just below it. Before the tolerance was added the first passed and the
     * other two did not, so the documented boundary held or not depending on the dose.
     */
    @Test
    fun isBolusShortfall_exactlyOneStepShortCounts() {
        listOf(1.0 to 0.975, 2.0 to 1.975, 0.15 to 0.125, 3.0 to 2.975, 0.5 to 0.475).forEach { (requested, delivered) ->
            assertThat(ApexDriverLogic.isBolusShortfall(requested, delivered, cancelled = false, provenFailure = false, bolusStep = bolusStep))
                .isTrue()
        }
    }

    /** Half a step is pump rounding, on either side of the representation. */
    @Test
    fun isBolusShortfall_lessThanOneStepIsNotAShortfall() {
        listOf(1.0 to 0.9875, 2.0 to 1.99, 0.15 to 0.14).forEach { (requested, delivered) ->
            assertThat(ApexDriverLogic.isBolusShortfall(requested, delivered, cancelled = false, provenFailure = false, bolusStep = bolusStep))
                .isFalse()
        }
    }

    @Test
    fun isLowReservoir_theCapturedCrossing() {
        // The real numbers from 27.08.2026: the pump's own level is 20 U, and the cartridge went from
        // 22.975 U to 19.975 U on one bolus. AAPS said nothing about it, because the driver read both
        // numbers and never compared them.
        assertThat(ApexDriverLogic.isLowReservoir(22.975, 20)).isFalse()
        assertThat(ApexDriverLogic.isLowReservoir(19.975, 20)).isTrue()
    }

    @Test
    fun isLowReservoir_theLevelItselfCounts() {
        // At exactly the configured level, warn. Half a pump step below it is the same answer, and one
        // step above it is not.
        assertThat(ApexDriverLogic.isLowReservoir(20.0, 20)).isTrue()
        assertThat(ApexDriverLogic.isLowReservoir(20.025, 20)).isFalse()
    }

    @Test
    fun isLowReservoir_anEmptyCartridgeIsLow() {
        assertThat(ApexDriverLogic.isLowReservoir(0.0, 20)).isTrue()
    }

    @Test
    fun isLowReservoir_noLevelConfiguredWarnsAboutNothing() {
        // A zero is not a level the user chose, and the pump has a second low-reservoir criterion by
        // time left, so a zero here does not even prove its own warning is off. Inventing a default
        // would put a card in front of the user at a level they never asked for.
        assertThat(ApexDriverLogic.isLowReservoir(19.975, 0)).isFalse()
        assertThat(ApexDriverLogic.isLowReservoir(0.0, 0)).isFalse()
    }

    // --- The second cartridge step, and the units it is compared in ---

    /**
     * [ApexDriverLogic.reservoirWarnings] for U100 insulin, where the cartridge reading and the
     * patient-side value are the same number. The concentration cases have their own test.
     */
    private fun atU100(levelU: Double, pumpLowThresholdU: Int, criticalThresholdIu: Int) =
        ApexDriverLogic.reservoirWarnings(
            levelPumpU = levelU,
            pumpLowThresholdPumpU = pumpLowThresholdU,
            levelIu = levelU,
            criticalThresholdIu = criticalThresholdIu,
        )

    private fun warnings(low: Boolean, critical: Boolean?) = ApexDriverLogic.ReservoirWarnings(low, critical)

    /** The run captured on 2026-08-31, against the pump's 20 U and the core default of 10 U. */
    @Test
    fun reservoirWarnings_theCapturedRun() {
        assertThat(atU100(53.950, 20, 10)).isEqualTo(warnings(low = false, critical = false))
        // 16:09:53, where the only card of that whole day went up.
        assertThat(atU100(18.375, 20, 10)).isEqualTo(warnings(low = true, critical = false))
        assertThat(atU100(10.925, 20, 10)).isEqualTo(warnings(low = true, critical = false))
        // 20:46:38, where the pump stopped. Nothing was said between this and the line above.
        assertThat(atU100(5.000, 20, 10)).isEqualTo(warnings(low = true, critical = true))
    }

    @Test
    fun reservoirWarnings_bothLevelsCountAtThemselves() {
        assertThat(atU100(20.0, 20, 10)).isEqualTo(warnings(low = true, critical = false))
        assertThat(atU100(10.0, 20, 10)).isEqualTo(warnings(low = true, critical = true))
        // One pump step above the critical level is still only the silent card.
        assertThat(atU100(10.025, 20, 10)).isEqualTo(warnings(low = true, critical = false))
    }

    /**
     * The two answers are independent, so nothing stops the user setting the critical level above
     * the pump's own. The loud card then arrives first and the silent one follows.
     */
    @Test
    fun reservoirWarnings_theCriticalLevelMayBeAboveThePumpsOwn() {
        assertThat(atU100(22.0, 20, 25)).isEqualTo(warnings(low = false, critical = true))
        assertThat(atU100(18.375, 20, 25)).isEqualTo(warnings(low = true, critical = true))
        assertThat(atU100(30.0, 20, 25)).isEqualTo(warnings(low = false, critical = false))
    }

    @Test
    fun reservoirWarnings_aZeroThresholdIsOffNotWarnAlways() {
        // Same meaning as for the pump's own setting: zero switches that step off. The level here is
        // 5 U throughout - the empty-cartridge case is its own test below.
        assertThat(atU100(5.0, 20, 0)).isEqualTo(warnings(low = true, critical = false))
        assertThat(atU100(5.0, 0, 0)).isEqualTo(warnings(low = false, critical = false))
        // The pump's own warning switched off must not silence the critical one.
        assertThat(atU100(5.0, 0, 10)).isEqualTo(warnings(low = false, critical = true))
    }

    @Test
    fun reservoirWarnings_anEmptyCartridgeIsBoth() {
        assertThat(atU100(0.0, 20, 10)).isEqualTo(warnings(low = true, critical = true))
    }

    /**
     * The critical threshold is a core preference in patient-side units and the pump's own threshold
     * is a number set on the pump, so the two are compared against different readings of the same
     * cartridge. Getting this wrong is silent: the loud card simply arrives at the wrong level.
     */
    @Test
    fun reservoirWarnings_theCriticalLevelFollowsInsulinConcentration() {
        // U200: 8 U in the cartridge is 16 IU to the patient, so a critical level of 10 IU is not
        // reached yet - while comparing the raw 8 against 10 would already have rung.
        assertThat(
            ApexDriverLogic.reservoirWarnings(
                levelPumpU = 8.0, pumpLowThresholdPumpU = 20, levelIu = 16.0, criticalThresholdIu = 10
            )
        ).isEqualTo(warnings(low = true, critical = false))

        // U50: 16 U in the cartridge is only 8 IU, so 10 IU is already crossed - while comparing the
        // raw 16 against 10 would have stayed quiet down to half the insulin the user asked for.
        assertThat(
            ApexDriverLogic.reservoirWarnings(
                levelPumpU = 16.0, pumpLowThresholdPumpU = 20, levelIu = 8.0, criticalThresholdIu = 10
            )
        ).isEqualTo(warnings(low = true, critical = true))

        // The pump's own low threshold keeps using the pump's own reading, whatever the concentration.
        // These numbers are chosen so the two wirings disagree: on U200 a cartridge at 15 U is 30 IU,
        // so reading the pump's 20 U threshold against the converted level would answer false and the
        // silent card would never appear.
        assertThat(
            ApexDriverLogic.reservoirWarnings(
                levelPumpU = 15.0, pumpLowThresholdPumpU = 20, levelIu = 30.0, criticalThresholdIu = 10
            )
        ).isEqualTo(warnings(low = true, critical = false))
    }

    /**
     * No running profile: the conversion is unknown, so the critical answer is null rather than
     * false. The caller leaves that card exactly as it is - answering false would take it down and
     * re-arm the crossing, and the siren would sound again the moment a profile came back.
     */
    @Test
    fun reservoirWarnings_withoutAProfileTheCriticalAnswerIsUnknown() {
        assertThat(
            ApexDriverLogic.reservoirWarnings(
                levelPumpU = 5.0, pumpLowThresholdPumpU = 20, levelIu = null, criticalThresholdIu = 10
            )
        ).isEqualTo(warnings(low = true, critical = null))

        // The pump's own card is unaffected by a missing profile - it needs no conversion.
        assertThat(
            ApexDriverLogic.reservoirWarnings(
                levelPumpU = 50.0, pumpLowThresholdPumpU = 20, levelIu = null, criticalThresholdIu = 10
            )
        ).isEqualTo(warnings(low = false, critical = null))
    }

    // ---- connection password, phase 1 -------------------------------------------------------

    /**
     * The frame is the six digits TWICE, and the halves are equal.
     *
     * Not a formatting detail: a password CHANGE is also twelve bytes, old followed by new, so equal
     * halves are the only thing that makes this a check rather than a change. This test is what keeps
     * that guarantee from being refactored away.
     */
    @Test
    fun passwordFrame_isTheDigitsTwiceWithEqualHalves() {
        val frame = ApexDriverLogic.passwordFrame("123456")
        assertThat(frame).isNotNull()
        assertThat(frame!!.size).isEqualTo(12)
        assertThat(frame.toList()).isEqualTo("123456123456".toByteArray(Charsets.US_ASCII).toList())
        assertThat(frame.copyOfRange(0, 6).toList()).isEqualTo(frame.copyOfRange(6, 12).toList())
    }

    @Test
    fun passwordFrame_defaultOfSixZerosIsAValidFrame() {
        // The maker's own convention for "no password set", and this driver's default.
        val frame = ApexDriverLogic.passwordFrame("000000")
        assertThat(frame).isNotNull()
        assertThat(frame!!.toList()).isEqualTo("000000000000".toByteArray(Charsets.US_ASCII).toList())
    }

    /**
     * Anything that is not exactly six digits produces no frame at all.
     *
     * This is the import path: a settings import writes preferences directly and never runs the
     * preference validator, so this check is the only one left between a malformed value and the pump.
     */
    @Test
    fun passwordFrame_rejectsAnythingThatIsNotSixDigits() {
        for (bad in listOf("", "12345", "1234567", "12345a", "12 456", "abcdef", " 12345", "12345\t"))
            assertThat(ApexDriverLogic.passwordFrame(bad)).isNull()
    }

    /** Every byte and every length maps to something: this runs where an exception has nothing above it. */
    @Test
    fun passwordReply_isTotal() {
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(0))).isEqualTo(ApexDriverLogic.PasswordReply.Accepted)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(1))).isEqualTo(ApexDriverLogic.PasswordReply.Rejected)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(2))).isEqualTo(ApexDriverLogic.PasswordReply.ChangeAnswer)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(3))).isEqualTo(ApexDriverLogic.PasswordReply.ChangeAnswer)
        // Outside the maker's own 0..3, and outside its one-byte shape.
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(4))).isEqualTo(ApexDriverLogic.PasswordReply.Unknown)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(-1))).isEqualTo(ApexDriverLogic.PasswordReply.Unknown)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf())).isEqualTo(ApexDriverLogic.PasswordReply.Unknown)
        assertThat(ApexDriverLogic.passwordReply(byteArrayOf(0, 0))).isEqualTo(ApexDriverLogic.PasswordReply.Unknown)
    }

    /** With the toggle off nothing is built, so nothing can be written to the pump. The default state. */
    @Test
    fun passwordToSend_sendsNothingWhileTheToggleIsOff() {
        assertThat(ApexDriverLogic.passwordToSend(hasPassword = false, password = "123456", alreadyRejected = null)).isNull()
        // Not even when a perfectly good password is stored, and not even after a rejection was cleared.
        assertThat(ApexDriverLogic.passwordToSend(hasPassword = false, password = "000000", alreadyRejected = "999999")).isNull()
    }

    /**
     * A rejected password is not sent again until it is changed.
     *
     * The memory holds the VALUE, not a flag, so editing the setting is what makes the driver try
     * again - there is no separate reset to forget. Without this a reconnect loop would feed a wrong
     * password to the pump over and over.
     */
    @Test
    fun passwordToSend_doesNotRepeatARejectedPassword() {
        assertThat(ApexDriverLogic.passwordToSend(true, "123456", alreadyRejected = "123456")).isNull()
        // A different password is a new attempt, which is what fixing it in settings produces.
        assertThat(ApexDriverLogic.passwordToSend(true, "654321", alreadyRejected = "123456")).isNotNull()
        // Nothing rejected yet.
        assertThat(ApexDriverLogic.passwordToSend(true, "123456", alreadyRejected = null)).isNotNull()
    }

    /** The import path: a malformed value never becomes a frame, whatever the toggle says. */
    @Test
    fun passwordToSend_refusesAMalformedStoredValue() {
        assertThat(ApexDriverLogic.passwordToSend(true, "", alreadyRejected = null)).isNull()
        assertThat(ApexDriverLogic.passwordToSend(true, "12345", alreadyRejected = null)).isNull()
        assertThat(ApexDriverLogic.passwordToSend(true, "abcdef", alreadyRejected = null)).isNull()
    }

    /**
     * Every descriptor write promotes the handshake except the password reply's, and none does on a
     * superseded client.
     *
     * Five cases, of which a hardware run exercises one. Before phase 1 there was exactly one
     * descriptor write in the handshake, so any callback meant "the subscription is up"; the password
     * subscription makes that false, and this is what stops onConnect running twice.
     *
     * Deliberately an exclusion rather than an allow-list: a predicate that promoted only on the read
     * characteristic would stop promoting on a null descriptor, which the code promoted before this
     * change, and a pump whose stack hands one back would never connect.
     */
    @Test
    fun promotesConnection_forEveryDescriptorExceptThePasswordReply() {
        val read = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        val passwordNotify = UUID.fromString("0000FFC2-0000-1000-8000-00805F9B34FB")

        assertThat(ApexBluetooth.promotesConnection(read, superseded = false)).isTrue()
        assertThat(ApexBluetooth.promotesConnection(passwordNotify, superseded = false)).isFalse()
        assertThat(ApexBluetooth.promotesConnection(read, superseded = true)).isFalse()
        assertThat(ApexBluetooth.promotesConnection(passwordNotify, superseded = true)).isFalse()
        // Null promotes, because it did before this change. The predicate excludes the one descriptor
        // phase 1 added and leaves every other path exactly as it was - a pump whose stack hands back
        // a null descriptor must keep connecting.
        assertThat(ApexBluetooth.promotesConnection(null, superseded = false)).isTrue()
    }

    /** Compact caller for the promotion table: sensible "current, FFE4 ok, Accepted" defaults. */
    private fun action(
        ffe4Issued: Boolean = true,
        callbackIsCurrent: Boolean = true,
        callbackKind: CallbackKind = CallbackKind.Ffe4,
        ffe4Success: Boolean = true,
        reply: PasswordReply? = PasswordReply.Accepted,
    ) = ApexDriverLogic.passwordHandshakeAction(ffe4Issued, callbackIsCurrent, callbackKind, ffe4Success, reply)

    @Test
    fun handshakeAction_gateOnFfe4OkAlwaysPromotes() {
        for (reply in listOf(PasswordReply.Accepted, PasswordReply.Rejected, null, PasswordReply.ChangeAnswer, PasswordReply.Unknown))
            assertThat(action(callbackKind = CallbackKind.Ffe4, ffe4Success = true, reply = reply)).isEqualTo(HandshakeAction.Promote)
    }

    @Test
    fun handshakeAction_gateOnFfe4FailLocksUnlessAccepted() {
        assertThat(action(ffe4Success = false, reply = PasswordReply.Accepted)).isEqualTo(HandshakeAction.RefuseNoLock)
        for (reply in listOf(PasswordReply.Rejected, null, PasswordReply.ChangeAnswer, PasswordReply.Unknown))
            assertThat(action(ffe4Success = false, reply = reply)).isEqualTo(HandshakeAction.RefuseAndLock)
    }

    @Test
    fun handshakeAction_staleCallbackIgnored() {
        assertThat(action(callbackIsCurrent = false, ffe4Success = false, reply = PasswordReply.Rejected)).isEqualTo(HandshakeAction.Ignore)
    }

    @Test
    fun handshakeAction_beforeFfe4IssuedNothingActs() {
        assertThat(action(ffe4Issued = false, callbackKind = CallbackKind.Other)).isEqualTo(HandshakeAction.Ignore)
        assertThat(action(ffe4Issued = false, callbackKind = CallbackKind.NullDescriptor)).isEqualTo(HandshakeAction.Ignore)
    }

    @Test
    fun handshakeAction_nullDescriptorAfterIssueFollowsFfe4() {
        // The stack may report the FFE4 completion with a null descriptor: treat it as FFE4.
        assertThat(action(callbackKind = CallbackKind.NullDescriptor, ffe4Success = true)).isEqualTo(HandshakeAction.Promote)
        assertThat(action(callbackKind = CallbackKind.NullDescriptor, ffe4Success = false, reply = PasswordReply.Rejected)).isEqualTo(HandshakeAction.RefuseAndLock)
        assertThat(action(callbackKind = CallbackKind.NullDescriptor, ffe4Success = false, reply = PasswordReply.Accepted)).isEqualTo(HandshakeAction.RefuseNoLock)
    }

    @Test
    fun handshakeAction_lateFfc2AfterIssueIgnored() {
        // A late FFC2 CCC once FFE4 is out is latch-only, never a refuse.
        assertThat(action(callbackKind = CallbackKind.Other, ffe4Success = false, reply = PasswordReply.Rejected)).isEqualTo(HandshakeAction.Ignore)
    }

    @Test
    fun shouldAttemptConnect_lockedWhenPairMatchesAndToggleOn() {
        assertThat(
            ApexDriverLogic.shouldAttemptConnect("33310677", "123456", "33310677", "123456", liveHasPassword = true, destroyed = false, manualDisconnect = false)
        ).isFalse()
    }

    @Test
    fun shouldAttemptConnect_toggleOffLiftsTheLockout() {
        assertThat(
            ApexDriverLogic.shouldAttemptConnect("33310677", "123456", "33310677", "123456", liveHasPassword = false, destroyed = false, manualDisconnect = false)
        ).isTrue()
    }

    @Test
    fun shouldAttemptConnect_changedPasswordLiftsTheLockout() {
        assertThat(
            ApexDriverLogic.shouldAttemptConnect("33310677", "123456", "33310677", "654321", liveHasPassword = true, destroyed = false, manualDisconnect = false)
        ).isTrue()
    }

    @Test
    fun shouldAttemptConnect_neverWhileDestroyedOrManual() {
        assertThat(
            ApexDriverLogic.shouldAttemptConnect(null, null, "33310677", "123456", liveHasPassword = true, destroyed = true, manualDisconnect = false)
        ).isFalse()
        assertThat(
            ApexDriverLogic.shouldAttemptConnect(null, null, "33310677", "123456", liveHasPassword = true, destroyed = false, manualDisconnect = true)
        ).isFalse()
    }

    @Test
    fun isPasswordLockout_onlyWhenPairMatchesAndToggleOn() {
        assertThat(ApexDriverLogic.isPasswordLockout("33310677", "123456", "33310677", "123456", liveHasPassword = true)).isTrue()
        assertThat(ApexDriverLogic.isPasswordLockout("33310677", "123456", "33310677", "123456", liveHasPassword = false)).isFalse()
        assertThat(ApexDriverLogic.isPasswordLockout(null, null, "33310677", "123456", liveHasPassword = true)).isFalse()
        assertThat(ApexDriverLogic.isPasswordLockout("33310677", "123456", "99999999", "123456", liveHasPassword = true)).isFalse()
    }

    @Test
    fun forceUpdatePasswordBytes_isSixValueBytesNotAscii() {
        assertThat(ApexDriverLogic.forceUpdatePasswordBytes("123456")).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertThat(ApexDriverLogic.forceUpdatePasswordBytes("000000")).isEqualTo(byteArrayOf(0, 0, 0, 0, 0, 0))
    }

    @Test
    fun forceUpdatePasswordBytes_nullForAnythingButSixDigits() {
        for (bad in listOf("", "12345", "1234567", "12345a", "abcdef", "12 456"))
            assertThat(ApexDriverLogic.forceUpdatePasswordBytes(bad)).isNull()
    }
}
