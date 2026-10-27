package app.aaps.plugins.boostport.activity

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector as Port
import app.aaps.plugins.aps.openAPSBoost.activity.SleepHistoryTracker as PortHistory
import app.aaps.plugins.aps.openAPSBoost.activity.MealTimeLearner as PortMeals
import app.aaps.plugins.aps.openAPSBoost.activity.DailyStepHistoryTracker as PortSteps
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector as Source
import app.aaps.plugins.boostport.reference.activity.SleepHistoryTracker as SourceHistory
import app.aaps.plugins.boostport.reference.activity.MealTimeLearner as SourceMeals
import app.aaps.plugins.boostport.reference.activity.DailyStepHistoryTracker as SourceSteps
import kotlinx.serialization.json.Json
import kotlin.test.*

class ActivitySourceParityTest {
    @Test fun completeSleepStateMachineMatchesSourceAcrossTwoDaysAndRestarts() {
        val base = 1_700_000_000_000L
        for (deadFeed in listOf(false, true)) {
            var port = Port.State()
            var source = Source.State()
            repeat(576) { n ->
                val now = base + n * 5 * 60_000L
                val minute = n * 5 % 1440
                val sleeping = minute >= 1320 || minute < 360
                val hr = if (deadFeed && sleeping) emptyList() else (1..5).map { i ->
                    HR(duration = 60_000L, timestamp = now - i * 60_000L,
                        beatsPerMinute = if (sleeping) 58.0 + i else 80.0 + i, device = "watch")
                }
                val steps = if (sleeping) 0 else 200
                val today = if (sleeping) 0 else (minute - 360) * 8
                val actual = Port.evaluate(port, Port.Inputs(now, minute, hr, hrResting = 60,
                    stepsLast15Min = steps, mlMealLikely = null, nightStartMin = 1320, nightEndMin = 420,
                    stepsToday = today, sleepInWindowMin = 60, sleepInStepsThreshold = 150))
                val expected = Source.evaluate(source, Source.Inputs(now, minute, hr, hrResting = 60,
                    stepsLast15Min = steps, mlMealLikely = null, nightStartMin = 1320, nightEndMin = 420,
                    stepsToday = today, sleepInWindowMin = 60, sleepInStepsThreshold = 150))
                assertEquals(expected.toString(), actual.toString(), "cycle $n dead=$deadFeed")
                port = actual.newState
                source = expected.newState
                assertEquals(Json.parseToJsonElement(source.serialize()), Json.parseToJsonElement(port.serialize()))
                if (n % 17 == 0) {
                    port = Port.State.deserialize(port.serialize())
                    source = Source.State.deserialize(source.serialize())
                }
            }
        }
    }

    @Test fun historicalJsonUsesOriginalFieldNamesDefaultsAndNullHandling() {
        val inputs = listOf("", "{broken", "{}", "null",
            """{"state":"SLEEPING","enteredAtMs":5000,"stepSamples":[[1000,3],[5000,100]],"sleepCandidateSinceMs":null}""",
            """{"sessions":[{"sleepStartMs":1000,"wakeMs":2000,"wakeReason":"hr_steps","sleepHrP10":60}],"openSleepStartMs":null}""",
            """{"events":[1000,2000,3000]}""",
            """{"days":[{"d":1,"s":5000,"src":"wear"}]}""",
            """{"sources":{"wear":[{"d":1,"s":5000}]} }""",
            """{"d":2,"max":{"wear":1000,"phone":500}}""")
        fun same(a: String, b: String) = assertEquals(Json.parseToJsonElement(a), Json.parseToJsonElement(b))
        for (raw in inputs) {
            same(Source.State.deserialize(raw).serialize(), Port.State.deserialize(raw).serialize())
            same(SourceHistory.History.deserialize(raw).serialize(), PortHistory.History.deserialize(raw).serialize())
            same(SourceMeals.History.deserialize(raw).serialize(), PortMeals.History.deserialize(raw).serialize())
            same(SourceSteps.History.deserialize(raw).serialize(), PortSteps.History.deserialize(raw).serialize())
            same(SourceSteps.MultiSourceHistory.deserialize(raw).serialize(), PortSteps.MultiSourceHistory.deserialize(raw).serialize())
            same(SourceSteps.IntradayStepBank.deserialize(raw).serialize(), PortSteps.IntradayStepBank.deserialize(raw).serialize())
        }
    }
}
