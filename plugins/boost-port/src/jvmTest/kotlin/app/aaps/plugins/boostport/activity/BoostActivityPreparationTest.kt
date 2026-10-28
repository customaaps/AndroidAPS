package app.aaps.plugins.boostport.activity

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSBoost.activity.*
import app.aaps.plugins.boostport.reference.activity.BoostActivityPreparation as Source
import app.aaps.plugins.boostport.reference.activity.BoostActivitySnapshot as SourceSnapshot
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector as SourceSleep
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException

class BoostActivityPreparationTest {
    private val day = 1_700_000_000_000L
    private fun settings() = BoostActivitySettings("07:00", "22:00", 7 * 3_600_000L, 22 * 3_600_000L,
        1.0, 100, false, 100.0, 100, 130.0, 100, 500, 1000, 2000, 70.0,
        true, 15, 190, 60, true, 10, 22 * 3_600_000L, 7 * 3_600_000L)
    private fun snapshot(hour: Int, live: Boolean = true, steps15: Int = 0, asleep: Boolean = false) =
        BoostActivitySnapshot(day, "$hour:00", 0, steps15, steps15, steps15, live,
            SleepStateDetector.State(state = if (asleep) SleepStateDetector.SleepState.SLEEPING else SleepStateDetector.SleepState.AWAKE),
            null, null)

    private fun db(now: Long, elevatedHr: Boolean = false, cancel: Boolean = false): PersistenceLayer =
        Proxy.newProxyInstance(PersistenceLayer::class.java.classLoader, arrayOf(PersistenceLayer::class.java)) { _, method, _ ->
            if (cancel) throw CancellationException("cancelled")
            when (method.name) {
                "getStepsCountFromTimeToTime" -> emptyList<app.aaps.core.data.model.SC>()
                "getHeartRatesFromTime" -> if (!elevatedHr) emptyList() else (1..5).map { i ->
                    HR(duration = 60_000, timestamp = now - i * 60_000, beatsPerMinute = 110.0 + i, device = "watch")
                }
                else -> error("Unexpected activity query ${method.name}")
            }
        } as PersistenceLayer

    private fun <T> immediate(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return assertNotNull(value).getOrThrow()
    }

    private fun calculate(hour: Int, snapshot: BoostActivitySnapshot = snapshot(hour), hr: Boolean = false, percent: Int = 100): BoostActivityPreparation.BoostActivityResult {
        val now = day + hour * 3_600_000L
        return immediate { BoostActivityPreparation(settings(), snapshot, db(now, hr), formatHr = { "%.0f".format(it) })
            .calculate(now, false, 100.0, 100.0, 110.0, percent) }
    }

    @Test fun darkStepFeedDoesNotRaiseProfileAsIfTheUserWereInactive() {
        val result = calculate(13, snapshot(13, live = false))
        assertEquals(100, result.profileSwitch)
        assertEquals("steps-unknown", result.activityState)
        assertTrue(result.boostActive)
    }

    @Test fun clockNightAndConfirmedSleepBothCloseBoostAndTheInactivityRaise() {
        for (snapshot in listOf(snapshot(23), snapshot(13, asleep = true))) {
            val hour = if (snapshot.localTimeText == "23:00") 23 else 13
            val result = calculate(hour, snapshot)
            assertFalse(result.boostActive)
            assertEquals(100, result.profileSwitch)
        }
    }

    @Test fun exerciseProtectionStillActsOutsideTheBoostWindowAndPreservesUserPercentage() {
        val result = calculate(23, snapshot(23, steps15 = 600))
        assertFalse(result.boostActive)
        assertEquals(70, result.profileSwitch)
        assertEquals(150.0, result.targetBg)
        val userOverride = calculate(23, snapshot(23, steps15 = 600), percent = 120)
        assertEquals(120, userOverride.profileSwitch)
    }

    @Test fun elevatedHrWithFewStepsSuppressesTheInsulinAddingInactivityBranch() {
        val result = calculate(13, hr = true)
        assertEquals(100, result.profileSwitch)
        assertEquals(160.0, result.targetBg)
        assertEquals("RESISTANCE", result.activityState)
    }

    @Test fun fullActivityPreparationMatchesExtractedSourceAcrossClockFeedHrAndTargetContexts() {
        for (hour in listOf(6, 7, 8, 13, 22, 23)) for (live in listOf(false, true))
            for (steps in listOf(0, 600)) for (hr in listOf(false, true)) for (tt in listOf(false, true)) {
                val now = day + hour * 3_600_000L
                val captured = snapshot(hour, live, steps)
                val format: (Double?) -> String = { "%.0f".format(it) }
                val actual = immediate { BoostActivityPreparation(settings(), captured, db(now, hr), formatHr = format)
                    .calculate(now, tt, 160.0, 150.0, 170.0, 100) }
                val sourceSnapshot = SourceSnapshot(day, captured.localTimeText, 0, steps, steps, steps, live,
                    SourceSleep.State.deserialize(captured.sleep.serialize()), null, null)
                val expected = immediate { Source(settings(), sourceSnapshot, db(now, hr), formatHr = format)
                    .calculate(now, tt, 160.0, 150.0, 170.0, 100) }
                assertEquals(expected.toString(), actual.toString(), "$hour live=$live steps=$steps hr=$hr tt=$tt")
            }
    }

    @Test fun cancellationIsNotTreatedAsAnUnavailableStepFeed() {
        assertFailsWith<CancellationException> {
            immediate { BoostActivityPreparation(settings(), snapshot(13), db(day, cancel = true), formatHr = { "" })
                .calculate(day, false, 100.0, 100.0, 110.0, 100) }
        }
    }
    private fun resolved(atHour: Double, options: BoostActivitySettings = settings(),
        captured: BoostActivitySnapshot = snapshot(atHour.toInt(), live = false)): BoostActivityPreparation.BoostActivityResult {
        val at = day + (atHour * 3_600_000L).toLong()
        return immediate { BoostActivityPreparation(options, captured, db(at), formatHr = { "" })
            .calculate(at, false, 100.0, 100.0, 110.0, 100) }
    }
    @Test fun retiredBoostClocksCannotSilentlyRestrictDaytimeToTheOldOneMinuteDefault() {
        val options = settings().copy(boostStartTime = "bad", boostEndTime = "bad",
            boostStartOfDayMs = 7 * 3_600_000L, boostEndOfDayMs = 7 * 3_600_000L + 60_000L)
        assertTrue(resolved(13.0, options).boostActive)
        assertTrue(resolved(9.0, options).boostActive)
        assertFalse(resolved(23.0, options).boostActive)
    }
    @Test fun preSleepOnlyClosesTiersThroughTheSourceNightToggleButAlwaysExcludesInactivityRaise() {
        val preSleep = snapshot(13).copy(sleep = SleepStateDetector.State(state = SleepStateDetector.SleepState.PRE_SLEEP))
        for (enabled in listOf(false, true)) for (auto in listOf(false, true)) {
            val actual = resolved(13.0, settings().copy(nightModeEnabled = enabled, autoBySleep = auto), preSleep)
            assertEquals(!(enabled && auto), actual.boostActive)
            assertEquals(100, actual.profileSwitch)
        }
    }
    @Test fun learnedLaterWakeAnchorsTheLieInAndOnlyAGenuineWakeEndsItsBackstop() {
        val at = day + 10 * 3_600_000L + 30 * 60_000L
        val captured = snapshot(10).copy(effectiveNightEndMin = 600)
        val held = resolved(10.5, captured = captured)
        assertTrue(held.sleepInActive)
        assertFalse(held.boostActive)
        val woken = resolved(10.5, captured = captured.copy(lastGenuineWakeMs = at - 10 * 60_000L))
        assertTrue(woken.boostActive)
        assertEquals(100, woken.profileSwitch)
        val unknown = resolved(10.5, captured = captured.copy(phoneFeedLive = false))
        assertFalse(unknown.sleepInActive)
        assertTrue(unknown.boostActive)
        val sleeping = resolved(10.5, captured = captured.copy(phoneFeedLive = false,
            sleep = SleepStateDetector.State(state = SleepStateDetector.SleepState.SLEEPING)))
        assertFalse(sleeping.boostActive)
    }

}
