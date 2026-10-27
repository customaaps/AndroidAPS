package app.aaps.plugins.aps.openAPSBoost

/**
 * Offline, inspection-only transcription of the archived V1 profile-implied TDD guard.
 *
 * Source: legacy-source/openAPSBoost/OpenAPSBoostPlugin.kt.source
 * This helper has no caller in the active APS/pump code, and must not be used
 * to enable Boost, alter insulin sensitivity, or authorize therapy.
 */
internal fun boostV1TddImplausibleForProfileAudit(tdd: Double, profileSens: Double): Boolean {
    if (profileSens <= 0.0) return false
    val impliedTdd = 1800.0 / profileSens
    return tdd < impliedTdd * 0.35
}
