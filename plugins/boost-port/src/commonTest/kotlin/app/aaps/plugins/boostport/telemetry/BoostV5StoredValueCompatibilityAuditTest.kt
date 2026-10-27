package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5StoredValueCompatibilityAuditTest {
    private val audit = BoostV5StoredValueCompatibilityAudit

    @Test fun expectedKeysWithoutStoredValuesRemainAbsent() {
        val rows = audit.inspectAllExpected(emptyMap())
        assertEquals(8, rows.size)
        assertEquals(8, rows.count { it.finding == BoostV5StoredValueCompatibilityAudit.Finding.ABSENT })
    }

    @Test fun validStoredFactoryValueRemainsRecognized() {
        val rows = audit.inspect(mapOf("boost_v5_aggression" to "1.0"))
        assertEquals(BoostV5StoredValueCompatibilityAudit.Finding.MATCH, rows.single().finding)
    }

    @Test fun invalidAndOutOfBoundsValuesAreReportedWithoutClamping() {
        val rows = audit.inspect(mapOf(
            "boost_v5_aggression" to "2.0",
            "boost_v5_hypo_caution" to "broken",
            "boost_v5_confirmed_cap_u" to "NaN"
        )).associateBy { it.storageKey }
        assertEquals(BoostV5StoredValueCompatibilityAudit.Finding.OUT_OF_RANGE,
            rows.getValue("boost_v5_aggression").finding)
        assertEquals(2.0, rows.getValue("boost_v5_aggression").numericValue)
        assertEquals(BoostV5StoredValueCompatibilityAudit.Finding.NON_NUMERIC,
            rows.getValue("boost_v5_hypo_caution").finding)
        assertEquals(BoostV5StoredValueCompatibilityAudit.Finding.NON_FINITE,
            rows.getValue("boost_v5_confirmed_cap_u").finding)
    }

    @Test fun unknownKeyDoesNotPretendToBeBoostManagedSetting() {
        val rows = audit.inspect(mapOf("some_other_setting" to "1.0"))
        assertEquals(BoostV5StoredValueCompatibilityAudit.Finding.UNKNOWN_KEY, rows.single().finding)
        assertEquals(null, rows.single().numericValue)
    }
}
