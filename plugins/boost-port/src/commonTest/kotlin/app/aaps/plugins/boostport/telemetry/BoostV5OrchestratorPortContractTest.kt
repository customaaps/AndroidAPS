package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV5OrchestratorPortContractTest {
    private val contract = BoostV5OrchestratorPortContract

    @Test fun absentDependenciesPreventEngineReadiness() {
        val report = contract.inspect(emptySet())
        assertEquals(BoostV5OrchestratorPortContract.Finding.BLOCKED_MISSING_DEPENDENCIES, report.finding)
        assertEquals(BoostV5OrchestratorPortContract.Dependency.entries.toSet(), report.missing)
        assertFalse(report.liveExecutionSupported)
    }

    @Test fun boostSpecificOrefIsExplicitlyRequired() {
        val available = BoostV5OrchestratorPortContract.Dependency.entries.toSet() -
            BoostV5OrchestratorPortContract.Dependency.BOOST_OREF_BASE_INSULIN_REQ
        val report = contract.inspect(available)
        assertTrue(BoostV5OrchestratorPortContract.Dependency.BOOST_OREF_BASE_INSULIN_REQ in report.missing)
    }

    @Test fun satisfyingStructuralDependenciesDoesNotEnableDosing() {
        val report = contract.inspect(BoostV5OrchestratorPortContract.Dependency.entries.toSet())
        assertEquals(BoostV5OrchestratorPortContract.Finding.BLOCKED_LIVE_EXECUTION_UNSUPPORTED, report.finding)
        assertTrue(report.missing.isEmpty())
        assertFalse(report.liveExecutionSupported)
    }
}
