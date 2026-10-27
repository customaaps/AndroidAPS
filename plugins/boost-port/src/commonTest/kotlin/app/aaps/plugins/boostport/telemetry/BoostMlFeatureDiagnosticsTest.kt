package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoostMlFeatureDiagnosticsTest {
    private val ml = BoostMlFeatureDiagnostics
    private val minute = 60_000L

    private fun snap(ts: Long, cgm: Double, iob: Double = 0.0) =
        BoostMlFeatureDiagnostics.Snapshot(
            timestampMs = ts,
            cgmMgdl = cgm,
            iobIob = iob,
            iobActivity = 0.02,
            eventualBg = cgm + 7.0,
            recentSmbUnits60m = 0.5,
            minDelta = -2.0,
        )

    private fun buildRing(vararg snapshots: BoostMlFeatureDiagnostics.Snapshot) =
        snapshots.fold(BoostMlFeatureDiagnostics.Ring()) { ring, item -> ring.accept(item) }

    @Test fun exactlySixFiveMinuteCyclesAreRetained() {
        val ring = buildRing(*(0 until ml.LOOKBACK).map {
            snap(it * 5L * minute, 100.0 + it)
        }.toTypedArray())
        assertEquals(6, ring.size)
        assertEquals(100.0, ring.lagged(5)?.cgmMgdl)
        assertEquals(105.0, ring.lagged(0)?.cgmMgdl)
    }

    @Test fun onlyLastSixAreKeptAfterSeventhValidCycle() {
        val ring = buildRing(*(0..6).map { snap(it * 5L * minute, 100.0 + it) }.toTypedArray())
        assertEquals(6, ring.size)
        assertEquals(101.0, ring.lagged(5)?.cgmMgdl)
    }

    @Test fun twoHourGapDiscardsAllPreGapHistory() {
        val initial = buildRing(*(0 until 6).map {
            snap(it * 5L * minute, 100.0 + it)
        }.toTypedArray())
        val resumed = initial.accept(snap(120 * minute, 200.0))
        assertEquals(1, resumed.size)
        assertEquals(200.0, resumed.lagged(0)?.cgmMgdl)
        assertNull(resumed.lagged(1))
    }

    @Test fun shortGapKeepsPreviousReading() {
        val ring = buildRing(snap(0L, 100.0), snap(20 * minute, 110.0))
        assertEquals(2, ring.size)
        assertEquals(100.0, ring.lagged(1)?.cgmMgdl)
    }

    @Test fun thirtyFiveMinutesIsInclusiveButThirtySixExpires() {
        val kept = buildRing(snap(0L, 90.0), snap(35 * minute, 100.0))
        val expired = buildRing(snap(0L, 90.0), snap(36 * minute, 100.0))
        assertEquals(2, kept.size)
        assertEquals(1, expired.size)
    }

    @Test fun oneMinuteFeedsReplaceNewestSlotRatherThanCompressLagSpacing() {
        val ring = buildRing(
            snap(0L, 100.0),
            snap(1 * minute, 101.0),
            snap(2 * minute, 102.0),
            snap(3 * minute, 103.0),
        )
        assertEquals(1, ring.size)
        assertEquals(103.0, ring.lagged(0)?.cgmMgdl)
        assertNull(ring.lagged(1))
    }

    @Test fun thresholdFourMinutesThirtySecondsStartsNewSlot() {
        val before = buildRing(snap(0L, 100.0), snap(269_999L, 101.0))
        val at = buildRing(snap(0L, 100.0), snap(270_000L, 101.0))
        assertEquals(1, before.size)
        assertEquals(2, at.size)
    }

    @Test fun duplicateTimestampReplacesNewestSnapshot() {
        val ring = buildRing(snap(0L, 100.0), snap(0L, 120.0))
        assertEquals(1, ring.size)
        assertEquals(120.0, ring.lagged(0)?.cgmMgdl)
    }

    @Test fun backwardsClockFailsClosedInsteadOfForgingLag() {
        val ring = buildRing(snap(5 * minute, 100.0), snap(4 * minute, 200.0))
        assertEquals(0, ring.size)
    }

    @Test fun negativeClockFailsClosed() {
        val ring = buildRing(snap(0L, 100.0), snap(-1L, 200.0))
        assertEquals(0, ring.size)
    }

    @Test fun negativeOrOutOfRangeLagIsNotRead() {
        val ring = buildRing(snap(0L, 100.0))
        assertNull(ring.lagged(-1))
        assertNull(ring.lagged(6))
    }

    @Test fun coldRingDoesNotProduceAValidDiagnosticFeatureVector() {
        val current = snap(5 * minute, 110.0)
        assertNull(ml.build(listOf("cgm_mgdl_lag0"), current, BoostMlFeatureDiagnostics.Ring(), emptyMap()))
    }

    @Test fun mismatchedCurrentCycleFailsClosed() {
        val ring = buildRing(snap(0L, 100.0))
        assertNull(ml.build(listOf("cgm_mgdl_lag0"), snap(5 * minute, 200.0), ring, emptyMap()))
    }

    @Test fun lagVectorUsesDeclaredOrderNotMapIterationOrder() {
        val older = snap(0L, 100.0, 1.0)
        val current = snap(5 * minute, 150.0, 2.0)
        val ring = buildRing(older, current)
        val vector = ml.build(
            listOf("iob_iob_lag1", "cgm_mgdl", "cgm_mgdl_lag0", "cgm_mgdl_lag1",
                "iob_iob_lag0"),
            current, ring, mapOf("cgm_mgdl" to 150.0)
        )
        assertContentEquals(doubleArrayOf(1.0, 150.0, 150.0, 100.0, 2.0), vector)
    }

    @Test fun missingOlderLagUsesCurrentCycleLikeLegacyV12() {
        val current = snap(2 * minute, 180.0)
        val ring = buildRing(current)
        val vector = ml.build(
            listOf("cgm_mgdl_lag1", "cgm_mgdl_lag5"),
            current, ring, emptyMap()
        )
        assertContentEquals(doubleArrayOf(180.0, 180.0), vector)
    }

    @Test fun allSixLookbackFeatureNamesHaveKnownValues() {
        val current = snap(0L, 101.0, 2.0)
        val ring = buildRing(current)
        val names = ml.LOOKBACK_FEATURES.map { "${it}_lag0" }
        val result = ml.build(names, current, ring, emptyMap())
        assertContentEquals(doubleArrayOf(101.0, 2.0, 0.02, 108.0, 0.5, -2.0), result)
    }

    @Test fun invalidOrUnknownLagNamesCannotSilentlyAliasLagZero() {
        val current = snap(0L, 100.0)
        val ring = buildRing(current)
        for (name in listOf("cgm_mgdl_lagwat", "cgm_mgdl_lag6", "missing_lag1", "iob_iob_lag-1")) {
            assertNull(ml.build(listOf(name), current, ring, emptyMap()), name)
        }
    }

    @Test fun missingStaticAndNonFiniteValuesAreZeroForOfflineParityOnly() {
        val current = snap(0L, Double.NaN)
        val ring = buildRing(current)
        val result = ml.build(
            listOf("cgm_mgdl_lag0", "hour", "sug_COB", "iob_activity_lag0"),
            current, ring, mapOf("hour" to Double.POSITIVE_INFINITY, "sug_COB" to Double.NaN)
        )
        assertContentEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.02), result)
    }

    @Test fun stalePreRestartDataAreDroppedUponNextAcceptedCycle() {
        val restored = buildRing(snap(0L, 90.0), snap(5 * minute, 92.0))
        val afterRestart = restored.accept(snap(6 * 60 * minute, 150.0))
        assertEquals(1, afterRestart.size)
        assertContentEquals(
            doubleArrayOf(150.0, 150.0),
            ml.build(
                listOf("cgm_mgdl_lag1", "cgm_mgdl_lag2"),
                afterRestart.lagged(0)!!,
                afterRestart,
                emptyMap()
            )
        )
    }

    @Test fun emptyAndDuplicateFeatureSchemaCannotBeMisinterpretedAsValid() {
        val current = snap(0L, 100.0)
        val ring = buildRing(current)
        assertNull(ml.build(emptyList(), current, ring, emptyMap()))
        assertNull(ml.build(listOf("cgm_mgdl_lag0", "cgm_mgdl_lag0"), current, ring, emptyMap()))
        assertNotNull(ml.build(listOf("cgm_mgdl_lag0"), current, ring, emptyMap()))
        assertTrue(ring.snapshots().size == 1)
    }
}
