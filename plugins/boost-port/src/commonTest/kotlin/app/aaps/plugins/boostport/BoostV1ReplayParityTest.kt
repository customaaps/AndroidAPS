package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayParity
import app.aaps.plugins.aps.openAPSBoost.BoostV1ResultParity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1ReplayParityTest {
    private fun output() = BoostV1ResultParity.Output(
        algorithm = "BOOST",
        tempRequested = true,
        rate = 0.5,
        durationMinutes = 30,
        smbUnits = 0.0,
        targetBg = 100.0
    )

    private fun pair(scenario: BoostV1ReplayParity.Scenario): BoostV1ReplayParity.Pair =
        BoostV1ReplayParity.Pair(
            recordingId = scenario.name,
            referenceInputFingerprint = "recording-hash-${scenario.name}",
            candidateInputFingerprint = "recording-hash-${scenario.name}",
            scenario = scenario,
            reference = output(),
            candidate = output()
        )

    @Test fun requiresAllCriticalScenarios() {
        val summary = BoostV1ReplayParity.verify(listOf(pair(BoostV1ReplayParity.Scenario.NORMAL)))
        assertFalse(summary.valid)
        assertTrue(BoostV1ReplayParity.Scenario.HYPOGLYCEMIA in summary.missingScenarios)
    }

    @Test fun matchingBatchIsStructurallyAcceptedButNeverPumpEligible() {
        val summary = BoostV1ReplayParity.verify(BoostV1ReplayParity.Scenario.entries.map(::pair))
        assertTrue(summary.valid)
        assertFalse(summary.eligibleForPumpDispatch)
    }

    @Test fun smbMismatchFailsBatch() {
        val scenario = BoostV1ReplayParity.Scenario.SMB
        val original = pair(scenario)
        val altered = original.copy(candidate = original.candidate.copy(smbUnits = 0.2))
        val batch = BoostV1ReplayParity.Scenario.entries.map {
            if (it == scenario) altered else pair(it)
        }
        val summary = BoostV1ReplayParity.verify(batch)
        assertFalse(summary.valid)
        assertTrue(BoostV1ResultParity.Field.SMB_UNITS in summary.mismatches.first().fields)
    }

    @Test fun mismatchedInputSnapshotsCannotPass() {
        val repeated = pair(BoostV1ReplayParity.Scenario.NORMAL)
        val summary = BoostV1ReplayParity.verify(
            listOf(repeated.copy(candidateInputFingerprint = "different-snapshot")),
            emptySet()
        )
        assertFalse(summary.valid)
    }

    @Test fun duplicateAndUnidentifiedRecordingFails() {
        val repeated = pair(BoostV1ReplayParity.Scenario.NORMAL)
        val batch = listOf(repeated, repeated)
        assertFalse(BoostV1ReplayParity.verify(batch, emptySet()).valid)
        assertFalse(BoostV1ReplayParity.verify(listOf(repeated.copy(candidateInputFingerprint = "")), emptySet()).valid)
    }
}
