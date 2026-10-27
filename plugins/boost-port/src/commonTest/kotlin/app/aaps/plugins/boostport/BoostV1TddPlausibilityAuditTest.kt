package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.boostV1TddImplausibleForProfileAudit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline source-parity checks only; no pump or APS path. */
class BoostV1TddPlausibilityAuditTest {
    @Test fun exactLegacyBoundaryIsStrictlyLessThan() {
        // Profile ISF=60 implies TDD=30; 35% threshold is 10.5.
        assertTrue(boostV1TddImplausibleForProfileAudit(10.49, 60.0))
        assertFalse(boostV1TddImplausibleForProfileAudit(10.5, 60.0))
        assertFalse(boostV1TddImplausibleForProfileAudit(12.0, 60.0))
    }

    @Test fun nonpositiveProfileSensitivityUsesOriginalFalseFallback() {
        assertFalse(boostV1TddImplausibleForProfileAudit(0.0, 0.0))
        assertFalse(boostV1TddImplausibleForProfileAudit(0.0, -20.0))
    }

    @Test fun historicalIncompleteTddExampleIsClassified() {
        assertTrue(boostV1TddImplausibleForProfileAudit(6.6, 60.0))
    }

    @Test fun nonfiniteInputsRemainVisibleAsLegacyBehavior() {
        assertFalse(boostV1TddImplausibleForProfileAudit(Double.NaN, 60.0))
        assertFalse(boostV1TddImplausibleForProfileAudit(3.0, Double.NaN))
    }
}
