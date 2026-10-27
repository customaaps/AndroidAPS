package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

/** Offline regression cases only; these tests do not enable or exercise APS. */
class BoostMlRestartHistoryAuditTest {
    private val t0 = 1_700_000_000_000L
    private val step = 300_000L

    private fun row(ts: Long) = mapOf(
        "ts" to ts.toString(),
        "cgm_mgdl" to "110.0",
        "iob_iob" to "1.0",
        "iob_activity" to "0.1",
        "sug_eventualBG" to "115.0",
        "recent_smb_units_60m" to "0.0",
        "sug_minDelta" to "-0.2",
    )

    @Test fun sixRegularRowsRestoreAsComplete() {
        val rows = (0 until 6).map { row(t0 + it * step) }
        val result = BoostMlRestartHistoryAudit.inspect(rows, t0 + 5 * step)
        assertEquals(BoostMlRestartHistoryAudit.Finding.COMPLETE, result.finding)
        assertEquals(6, result.restoredCount)
    }

    @Test fun invalidHistoryIsCorruptEvenWhenItContainsFutureTimestamp() {
        val rows = listOf(row(t0) - "iob_iob", row(t0 + step * 2))
        assertEquals(
            BoostMlRestartHistoryAudit.Finding.CORRUPT,
            BoostMlRestartHistoryAudit.inspect(rows, t0 + step).finding,
        )
    }

    @Test fun validFutureTimestampCannotBeHiddenByRingReplacement() {
        val rows = listOf(row(t0), row(t0 + 60_000L))
        assertEquals(
            BoostMlRestartHistoryAudit.Finding.FUTURE_SNAPSHOT,
            BoostMlRestartHistoryAudit.inspect(rows, t0 + 30_000L).finding,
        )
    }

    @Test fun shortIrregularHistoryDoesNotMasqueradeAsColdStart() {
        val rows = listOf(row(t0), row(t0 + 600_000L))
        assertEquals(
            BoostMlRestartHistoryAudit.Finding.IRREGULAR_SPACING,
            BoostMlRestartHistoryAudit.inspect(rows, t0 + 600_000L).finding,
        )
    }

    @Test fun staleHistoryDoesNotMasqueradeAsValidRestart() {
        val rows = listOf(row(t0), row(t0 + step))
        assertEquals(
            BoostMlRestartHistoryAudit.Finding.STALE_RESTART,
            BoostMlRestartHistoryAudit.inspect(rows, t0 + step + 36 * 60_000L).finding,
        )
    }

    @Test fun shortIntervalReplacementPreservesDiagnosticSignal() {
        val rows = listOf(row(t0), row(t0 + 60_000L))
        val result = BoostMlRestartHistoryAudit.inspect(rows, t0 + 60_000L)
        assertEquals(BoostMlRestartHistoryAudit.Finding.COLD_START, result.finding)
        assertEquals(1, result.restoredCount)
        assertEquals(
            BoostMlPersistedFormatAudit.Finding.NORMALIZED_ON_RESTORE,
            BoostMlPersistedFormatAudit.inspect(rows).finding,
        )
    }
}
