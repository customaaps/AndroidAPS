package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostTddTransitionOfflineReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostTddTransitionOfflineReplayTest {
    @Test fun tddTransitionReplaysWithoutWritingSettings() {
        val replay = BoostTddTransitionOfflineReplay
        val result = replay.inspect(listOf(
            BoostTddTransitionOfflineReplay.Frame(1L, false, "v1", true, 50.0),
            BoostTddTransitionOfflineReplay.Frame(2L, true, "v1", true, 0.0),
            BoostTddTransitionOfflineReplay.Frame(3L, true, "v6", true, 100.0),
        ))
        assertEquals(BoostTddTransitionOfflineReplay.Finding.VALID, result.finding)
        assertEquals(0.0, result.transitions[0].result.velocityPct)
        assertFalse(result.transitions[0].result.adjustSens)
        assertEquals(100.0, result.transitions[1].result.velocityPct)
        assertFalse(result.transitions[2].result.adjustSens)
        assertTrue(result.transitions[2].result.clearedAdjustSens)
    }

    @Test fun nonFiniteAndOutOfOrderFramesFailClosed() {
        val replay = BoostTddTransitionOfflineReplay
        assertEquals(BoostTddTransitionOfflineReplay.Finding.EMPTY, replay.inspect(emptyList()).finding)
        assertEquals(BoostTddTransitionOfflineReplay.Finding.INVALID_VALUE,
            replay.inspect(listOf(BoostTddTransitionOfflineReplay.Frame(1L, true, "v1", true, Double.NaN))).finding)
        assertEquals(BoostTddTransitionOfflineReplay.Finding.INVALID_SEQUENCE,
            replay.inspect(listOf(BoostTddTransitionOfflineReplay.Frame(2L, true, "v1", true, 100.0),
                BoostTddTransitionOfflineReplay.Frame(1L, true, "v1", true, 100.0))).finding)
    }
}
