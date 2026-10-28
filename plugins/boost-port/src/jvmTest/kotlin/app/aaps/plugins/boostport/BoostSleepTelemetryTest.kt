package app.aaps.plugins.boostport

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSBoost.activity.SleepHistoryTracker
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import app.aaps.plugins.boostport.aps.BoostSleepCycleOwner
import app.aaps.plugins.boostport.aps.BoostSleepTelemetry
import app.aaps.plugins.boostport.reference.activity.SleepHistoryTracker as OriginalHistory
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector as OriginalSleep
import app.aaps.plugins.boostport.reference.sleeptelemetry.ReferenceSleepTelemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class BoostSleepTelemetryTest {
    private val now = 20_000L * 86_400_000L + 22 * 3_600_000L
    private fun hr(minutesAgo: Double, bpm: Double = 70.0, duration: Long = 60_000L,
        valid: Boolean = true, device: String = "Garmin") = HR(duration = duration,
        timestamp = now - (minutesAgo * 60_000L).toLong(), beatsPerMinute = bpm,
        device = device, isValid = valid)
    private val emptyLearned = SleepHistoryTracker.Aggregate(null, null, null, 0)
    private val learned = SleepHistoryTracker.Aggregate(1350, 450, 540, 8, 52, 66, 7, 7)
    private fun update(rows: List<HR>, state: SleepStateDetector.State = SleepStateDetector.State(),
        aggregate: SleepHistoryTracker.Aggregate = emptyLearned) = BoostSleepCycleOwner.Update(
        SleepStateDetector.Result(state, false, "test"), aggregate, 1320, 420, 0, rows)
    private fun rt() = RT(runningDynamicIsf = true, algorithm = APSResult.Algorithm.BOOST,
        units = 0.2, rate = 1.1, duration = 30, reason = StringBuilder("original"))

    @Test fun uploadBlockMatchesOriginalAcrossSleepLearningAndHeartRateContexts() {
        val sets = listOf(emptyList(), listOf(hr(1.0, 75.65)),
            listOf(hr(1.0, 60.0, 30_000L), hr(2.0, 80.0, 90_000L)),
            listOf(hr(1.0, 99.0, valid = false), hr(2.0, 60.0)),
            listOf(hr(1.0, 65.0, 0L), hr(5.0, 90.0, 0L)),
            listOf(hr(15.0), hr(5.0, 80.0), hr(0.0, 90.0)),
            listOf(hr(20.0, 55.0, device = "HealthConnect")),
            listOf(hr(1.0, device = "OPPO watch"), hr(2.0, device = "HealthConnect")),
            listOf(hr(12.0), hr(12.0 + 1.0 / 60_000.0, device = "HealthConnect")))
        var compared = 0
        for (sleep in SleepStateDetector.SleepState.entries) for (entered in listOf(0L, now - 10 * 60_000L))
            for (history in listOf(emptyLearned, learned)) for (rows in sets) {
                val state = SleepStateDetector.State(state = sleep, enteredAtMs = entered,
                    sleepEntryReason = "hr".takeIf { sleep == SleepStateDetector.SleepState.SLEEPING })
                val originalState = OriginalSleep.State(state = OriginalSleep.SleepState.valueOf(sleep.name),
                    enteredAtMs = entered, sleepEntryReason = state.sleepEntryReason)
                val originalHistory = OriginalHistory.Aggregate(history.sleepStartMinAvg, history.wakeMinAvg,
                    history.sleepDurationMinAvg, history.sessionCount, history.restingHrBpm, history.daytimeHrBpm,
                    history.restingHrSampleCount, history.daytimeHrSampleCount)
                val expected = ReferenceSleepTelemetry.calculate(originalState, originalHistory, rows, now)
                val actual = rt().also { BoostSleepTelemetry.write(it, update(rows, state, history), now) }
                val expectedJson = expected.toJson()
                val actualJson = Json.parseToJsonElement(actual.serialize()).jsonObject.filterKeys { it in ReferenceSleepTelemetry.fields }
                assertEquals(expectedJson.toMap(), actualJson, "$sleep $entered $history $rows")
                compared++
            }
        assertEquals(108, compared)
    }

    @Test fun invalidNewestReadingDoesNotResurrectOlderLatestButValidAveragesRemain() {
        val result = rt()
        BoostSleepTelemetry.write(result, update(listOf(hr(1.0, 90.0, valid = false), hr(2.0, 60.0))), now)
        assertNull(result.hrBpmLatest)
        assertEquals(60.0, result.hrBpmAvg5m)
        assertEquals(1, result.hrReadingsCount15m)
    }

    @Test fun sourceUsesDurationWeightsStrictCutoffsAndOneDecimalRounding() {
        val result = rt()
        BoostSleepTelemetry.write(result, update(listOf(hr(1.0, 60.0, 30_000L), hr(2.0, 80.0, 90_000L),
            hr(5.0, 100.0), hr(15.0, 200.0))), now)
        assertEquals(75.0, result.hrBpmAvg5m)
        assertEquals(83.3, result.hrBpmAvg15m)
        assertEquals(3, result.hrReadingsCount15m)
    }

    @Test fun telemetryDoesNotChangeDosingOrCanonicalReasonAndSurvivesSerializationAndCopy() {
        val result = rt()
        val before = Json.parseToJsonElement(result.serialize()).jsonObject
        BoostSleepTelemetry.write(result, update(listOf(hr(1.0, 75.65)), aggregate = learned), now)
        val restored = RT.deserialize(result.serialize())
        assertEquals(result, restored.copy(reason = result.reason))
        assertEquals(75.7, restored.hrBpmLatest)
        assertEquals(before.toMap(), Json.parseToJsonElement(result.serialize()).jsonObject.filterKeys { it !in ReferenceSleepTelemetry.fields })
        assertEquals("original", result.reason.toString())
        assertEquals(learned.sessionCount, result.copy().sleepLearnedSessionCount)
    }

    @Test fun nonfiniteValidTelemetryRejectsInsteadOfPoisoningDeviceStatusJson() {
        for (bpm in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                BoostSleepTelemetry.write(rt(), update(listOf(hr(1.0, bpm))), now)
            }
        }
        BoostSleepTelemetry.write(rt(), update(listOf(hr(1.0, Double.NaN, valid = false))), now)
    }

    @Test fun futureTimestampsRetainTheExistingPortFreshnessHardening() {
        // Real owner reads are bounded at now. Preserve the port's established
        // defensive resolver behavior even if a custom producer violates that contract.
        val result = rt()
        BoostSleepTelemetry.write(result, update(listOf(hr(-1.0))), now)
        assertNull(result.hrSource_resolved)
        assertEquals("garmin(-,1,-1m)", result.hrSource_states)
    }

    @Test fun oldResultsReadWithoutTelemetryAndOrdinaryAlgorithmsDoNotUploadNewNullKeys() {
        val old = """{"runningDynamicIsf":false,"sensitivityRatio":1.2}"""
        val result = RT.deserialize(old)
        assertNull(result.sleepState)
        assertNull(result.hrBpmLatest)
        assertEquals("""{"runningDynamicIsf":false,"sensitivityRatio":1.2,"reason":""}""", result.serialize())
    }
}
