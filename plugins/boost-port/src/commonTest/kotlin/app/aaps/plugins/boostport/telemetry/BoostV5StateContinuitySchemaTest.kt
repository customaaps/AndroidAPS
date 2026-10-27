package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure offline persisted-schema compatibility regressions. */
class BoostV5StateContinuitySchemaTest {
    private val continuity = BoostV5StateContinuityDiagnostics
    private val fields = mapOf("mealHypothesis" to "IDLE", "mealHypothesisAge" to "0")

    @Test fun supportedOlderSchemaStillRestores() {
        val inspected = continuity.inspect(continuity.restart(), fields, 1_700_000_000_000L)!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.PERSISTED, inspected.source)
    }

    @Test fun unknownNewerFieldMustNotBeDiscardedSilently() {
        val inspected = continuity.inspect(
            continuity.restart(), fields + ("futureLockState" to "true"), 1_700_000_000_000L
        )!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.REJECTED_PERSISTED, inspected.source)
        assertEquals(BoostV5StateSnapshotCodec.Snapshot(), inspected.snapshot)
    }

    @Test fun cacheOnlyFieldCannotBeResurrectedAsPersisted() {
        val inspected = continuity.inspect(
            continuity.restart(), fields + ("lastCycleScore" to "0.9"), 1_700_000_000_000L
        )!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.REJECTED_PERSISTED, inspected.source)
    }

    @Test fun validCachedStateWinsOverUnknownPersistedSchema() {
        val snapshot = BoostV5StateSnapshotCodec.Snapshot()
        val memory = continuity.stageSave(continuity.restart(), snapshot)!!
        val inspected = continuity.inspect(
            memory, fields + ("futureLockState" to "true"), 1_700_000_000_000L
        )!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.MEMORY, inspected.source)
        assertEquals(snapshot, inspected.snapshot)
    }
}
