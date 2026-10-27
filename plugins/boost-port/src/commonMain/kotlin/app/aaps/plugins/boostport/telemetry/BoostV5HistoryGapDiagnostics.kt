package app.aaps.plugins.boostport.telemetry

/**
 * Read-only KMP adaptation of BoostHistorySync's onboarding decisions.
 *
 * Emits a hypothetical decision for offline inspection ONLY. Never contacts
 * Nightscout, bypasses NSClient settings, syncs records, starts a background
 * task or changes AAPS dosing. An actual history-download integration remains
 * a separate, user-consented and independently verified change.
 */
internal object BoostV5HistoryGapDiagnostics {
    const val BACKFILL_DAYS = 14L
    const val MIN_DAYS_WITH_TDD = 7
    const val MIN_BG_READINGS = 1_500
    const val MIN_TREATMENTS = 50
    const val RETRY_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    const val MAX_ATTEMPTS = 3
    const val NEW_INSTALL_WINDOW_MS = 48L * 60L * 60L * 1000L

    data class History(
        val daysWithTdd: Int,
        val bgReadings: Int,
        val treatments: Int,
    )

    data class State(
        val attempts: Int = 0,
        val lastAttemptMs: Long = 0L,
        val preBgReadings: Int = 0,
        val preTreatments: Int = 0,
        val firstSeenMs: Long = 0L,
    )

    data class Decision(
        val wouldRequestBackfill: Boolean,
        val summary: String?,
        val nextState: State?,
    )

    fun isSufficient(history: History): Boolean =
        history.daysWithTdd >= MIN_DAYS_WITH_TDD &&
            history.bgReadings >= MIN_BG_READINGS &&
            history.treatments >= MIN_TREATMENTS

    private fun shortfall(h: History) =
        "days=${h.daysWithTdd}/$MIN_DAYS_WITH_TDD,bg=${h.bgReadings}/$MIN_BG_READINGS,tr=${h.treatments}/$MIN_TREATMENTS"

    private fun signed(value: Int) = if (value >= 0) "+$value" else "$value"

    /**
     * Mirrors the source's anchor, six-hour cooldown, 48-hour new-install
     * window and three-attempt limit. Guard invalid inputs and backwards
     * clock movement; the original inputs assume a stable wall clock.
     */
    fun inspect(
        history: History,
        nightscoutConfigured: Boolean,
        nowMs: Long,
        timestampLabel: String,
        state: State = State(),
    ): Decision {
        val quiet = Decision(false, null, null)
        if (nowMs <= 0L ||
            history.daysWithTdd < 0 || history.bgReadings < 0 || history.treatments < 0 ||
            state.attempts < 0 || state.lastAttemptMs < 0L ||
            state.firstSeenMs < 0L || state.preBgReadings < 0 || state.preTreatments < 0 ||
            state.lastAttemptMs > nowMs || state.firstSeenMs > nowMs
        ) return quiet

        val firstSeen = if (state.firstSeenMs == 0L) nowMs else state.firstSeenMs
        val stamped = state.copy(firstSeenMs = firstSeen)

        if (isSufficient(history)) {
            if (state.attempts <= 0) {
                return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)
            }
            return Decision(
                false,
                "filled:${BACKFILL_DAYS}d,treatments=${signed(history.treatments - state.preTreatments)}," +
                    "bg=${signed(history.bgReadings - state.preBgReadings)}@$timestampLabel",
                State(firstSeenMs = firstSeen)
            )
        }
        if (state.lastAttemptMs != 0L && nowMs - state.lastAttemptMs < RETRY_COOLDOWN_MS) {
            return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)
        }

        if (nowMs - firstSeen > NEW_INSTALL_WINDOW_MS) {
            if (state.lastAttemptMs == 0L && state.attempts == 0) {
                return Decision(
                    false,
                    "skipped:outside-new-install-window,${shortfall(history)}@$timestampLabel",
                    stamped.copy(lastAttemptMs = nowMs),
                )
            }
            return Decision(false, null, if (state.firstSeenMs == 0L) stamped else null)
        }
        if (!nightscoutConfigured) {
            return Decision(
                false,
                "skipped:ns-unavailable,${shortfall(history)}@$timestampLabel",
                stamped.copy(lastAttemptMs = nowMs),
            )
        }
        if (state.attempts >= MAX_ATTEMPTS) {
            return Decision(
                false,
                "exhausted:${state.attempts},${shortfall(history)}@$timestampLabel",
                stamped.copy(lastAttemptMs = nowMs),
            )
        }

        return Decision(
            true,
            "requested:${BACKFILL_DAYS}d,attempt=${state.attempts + 1}/$MAX_ATTEMPTS," +
                "${shortfall(history)}@$timestampLabel",
            State(
                attempts = state.attempts + 1,
                lastAttemptMs = nowMs,
                preBgReadings = history.bgReadings,
                preTreatments = history.treatments,
                firstSeenMs = firstSeen,
            ),
        )
    }
}
