package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Offline ordering tests only; no Preferences or APS execution. */
class BoostV5StateStoreSequenceAuditTest {
    private val audit = BoostV5StateStoreSequenceAudit

    @Test fun saveStagesCacheBeforePersistenceRequest() {
        val state = BoostV5StateSnapshotCodec.Snapshot()
        val trace = audit.inspectSave(state)
        assertNotNull(trace)
        assertEquals(listOf(
            BoostV5StateStoreSequenceAudit.Event.CACHE_SET,
            BoostV5StateStoreSequenceAudit.Event.PERSIST_REQUEST,
        ), trace.events)
        assertEquals(BoostV5StateSnapshotCodec.encode(state), trace.persistedFields)
    }

    @Test fun clearStagesCacheInvalidationBeforePersistenceClear() {
        val trace = audit.inspectClear()
        assertEquals(listOf(
            BoostV5StateStoreSequenceAudit.Event.CACHE_CLEAR,
            BoostV5StateStoreSequenceAudit.Event.PERSIST_CLEAR_REQUEST,
        ), trace.events)
        assertNull(trace.persistedFields)
    }

    @Test fun invalidStateDoesNotProduceSaveTrace() {
        val invalid = BoostV5StateSnapshotCodec.Snapshot(primerAppliedU = Double.NaN)
        assertNull(audit.inspectSave(invalid))
    }
}
