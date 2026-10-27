package app.aaps.plugins.boostport.telemetry

/**
 * Passive pure-KMP adaptation of Boost V1 StepFeed source-availability and
 * night/inactivity/sleep-in predicates (2026-07-07 and 2026-07-31 fixes).
 *
 * A missing step source is UNKNOWN activity, not measured inactivity.
 * The configured night window is a CLOCK FACT independent of the policy
 * switch, and ineligible for the source's inactive branch. This object
 * only labels a hypothetical offline data sample. It has NO access to
 * Android sensors, profiles, insulin/SMB calculations, APS or pump commands.
 */
internal object StepFeedSafetyDiagnostics {
    const val WEAR_FRESH_MS = 12L * 60_000L

    data class Feed(
        val phoneReportedThisBoot: Boolean,
        val latestWearAgeMs: Long?,
    )

    data class Classification(
        val feedLabel: String,
        val feedAvailable: Boolean,
        val inactivityStepsMet: Boolean,
        val hypotheticallyInactivityEligible: Boolean,
        val hypotheticallyInLieIn: Boolean,
        val hypotheticallyLieInFailsafeNeeded: Boolean,
        val note: String?,
    )

    data class Context(
        val feed: Feed,
        val currentProfileSwitchPct: Int,
        val observedStepsLast60Min: Int?,
        val inactivityStepsThreshold: Int,
        val currentlyAsleepOrPreSleep: Boolean,
        val inConfiguredNightClock: Boolean,
        val nightModeEnabled: Boolean,
        val autoBySleepActive: Boolean,
        val detectorSleeping: Boolean,
        val nowMs: Long,
        val nightEndMs: Long,
        val sleepInWindowMs: Long,
        val sleepInStepsThreshold: Int,
    )

    private fun wearFresh(feed: Feed): Boolean =
        feed.latestWearAgeMs?.let { it in 0L..WEAR_FRESH_MS } ?: false

    fun feedLabel(feed: Feed): String {
        val wear = wearFresh(feed)
        return when {
            feed.phoneReportedThisBoot && wear -> "phone+wear"
            feed.phoneReportedThisBoot -> "phone"
            wear -> "wear"
            else -> "none"
        }
    }

    fun unavailableNote(feed: Feed): String {
        val phone = if (feed.phoneReportedThisBoot) "live" else "none-this-boot"
        val wear = feed.latestWearAgeMs?.let { age ->
            if (age < 0L) "future-invalid" else "stale ${age / 60_000L}m"
        } ?: "none"
        return "steps:UNAVAILABLE(phone=$phone, wear=$wear)"
    }

    /**
     * Null: invalid clock, no evidence of a numeric threshold, or a corrupt
     * feed timestamp. No value emitted by this method is written to AAPS.
     */
    fun inspect(context: Context): Classification? {
        val c = context
        if (c.feed.latestWearAgeMs?.let { it < 0L } == true ||
            c.nowMs < 0L || c.nightEndMs < 0L || c.sleepInWindowMs < 0L ||
            c.sleepInWindowMs > 24L * 60L * 60_000L ||
            c.nightEndMs > Long.MAX_VALUE - c.sleepInWindowMs ||
            c.currentProfileSwitchPct !in 0..500 ||
            c.inactivityStepsThreshold <= 0 || c.sleepInStepsThreshold <= 0 ||
            c.observedStepsLast60Min?.let { it < 0 } == true
        ) return null

        val label = feedLabel(c.feed)
        val available = label != "none"
        val stepsKnown = available && c.observedStepsLast60Min != null
        val inactiveSteps = stepsKnown && c.currentProfileSwitchPct == 100 &&
            c.observedStepsLast60Min!! < c.inactivityStepsThreshold
        val lieIn = stepsKnown && c.nowMs >= c.nightEndMs &&
            c.nowMs < c.nightEndMs + c.sleepInWindowMs &&
            c.observedStepsLast60Min!! < c.sleepInStepsThreshold
        val inactivity = inactiveSteps && !lieIn &&
            !c.currentlyAsleepOrPreSleep && !c.inConfiguredNightClock
        val failsafe = lieIn && !(c.nightModeEnabled &&
            c.autoBySleepActive && c.detectorSleeping)

        return Classification(
            feedLabel = label,
            feedAvailable = available,
            inactivityStepsMet = inactiveSteps,
            hypotheticallyInactivityEligible = inactivity,
            hypotheticallyInLieIn = lieIn,
            hypotheticallyLieInFailsafeNeeded = failsafe,
            note = if (available) null else unavailableNote(c.feed),
        )
    }
}
