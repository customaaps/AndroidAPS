package app.aaps.plugins.boostport.telemetry

/**
 * Inert, pure KMP replay of the cumulative steps-since-local-midnight ledger
 * in Boost V1 StepService (2026-06-19/24), distinct from its 5-minute buckets.
 *
 * Every event is supplied by an offline caller, including the local day index.
 * This class NEVER reads an Android sensor, Health Connect, Preferences, or
 * AAPS data and cannot affect ISF, target BG, SMB or a pump.
 *
 * Counter resets (device reboot) contribute the new counter since reboot;
 * HC today totals only ever raise the known maximum (HOLD HIGHER).
 * A day rollover establishes a new raw baseline, not yesterday's carryover.
 */
internal object PhoneStepDayLedgerDiagnostics {
    data class State(
        val localDayIndex: Long = -1L,
        val rawCounter: Long? = null,
        val stepsToday: Long = 0L,
        val hasDailyEvidence: Boolean = false,
        val sensorEverReported: Boolean = false,
    )

    enum class Event { DAY_STARTED, RAW_BASELINE, RAW_INCREMENT, SENSOR_RESET, NO_READING, HC_SEED, HC_NO_CHANGE }

    data class Update(
        val state: State,
        val event: Event,
        val reportedStepsToday: Long?,
        val feedKnown: Boolean,
    )

    private fun valid(s: State): Boolean =
        s.localDayIndex >= -1L &&
            (s.localDayIndex != -1L || (s.rawCounter == null &&
                s.stepsToday == 0L && !s.hasDailyEvidence)) &&
            s.rawCounter?.let { it < 0L } != true &&
            s.stepsToday >= 0L

    private fun result(s: State, event: Event): Update =
        Update(
            state = s,
            event = event,
            reportedStepsToday = if (s.hasDailyEvidence) s.stepsToday else null,
            feedKnown = s.sensorEverReported,
        )

    private fun addBounded(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    /**
     * Simulate a read of the Android hardware cumulative counter.
     * No callback after boot means null raw counter, not zero steps.
     */
    fun observeRaw(before: State, localDayIndex: Long, rawCounter: Long?): Update? {
        if (!valid(before) || localDayIndex < 0L ||
            (before.localDayIndex >= 0L && localDayIndex < before.localDayIndex) ||
            rawCounter?.let { it < 0L } == true
        ) return null
        val seenSensor = before.sensorEverReported || rawCounter != null

        if (before.localDayIndex != localDayIndex) {
            return result(
                State(
                    localDayIndex = localDayIndex,
                    rawCounter = rawCounter,
                    stepsToday = 0L,
                    hasDailyEvidence = rawCounter != null,
                    sensorEverReported = seenSensor,
                ), Event.DAY_STARTED,
            )
        }

        if (rawCounter == null)
            return result(before, Event.NO_READING)

        val old = before.rawCounter
        if (old == null) {
            val next = before.copy(
                rawCounter = rawCounter,
                hasDailyEvidence = true,
                sensorEverReported = true,
            )
            return result(next, Event.RAW_BASELINE)
        }

        val reset = rawCounter < old
        val increment = if (reset) rawCounter else rawCounter - old
        val next = before.copy(
            rawCounter = rawCounter,
            stepsToday = addBounded(before.stepsToday, increment),
            hasDailyEvidence = true,
            sensorEverReported = true,
        )
        return result(next, if (reset) Event.SENSOR_RESET else Event.RAW_INCREMENT)
    }

    /**
     * Offline HC reconciliation: preserve the highest known current-day
     * total. Null means no HC reading (never treat as measured zero).
     */
    fun seedHealthConnect(
        before: State,
        localDayIndex: Long,
        hcStepsToday: Long?,
        currentRawCounter: Long?,
    ): Update? {
        if (!valid(before) || localDayIndex < 0L ||
            (before.localDayIndex >= 0L && localDayIndex < before.localDayIndex) ||
            hcStepsToday?.let { it < 0L } == true ||
            currentRawCounter?.let { it < 0L } == true
        ) return null

        val nextDay = before.localDayIndex != localDayIndex
        val base = if (nextDay) {
            State(
                localDayIndex = localDayIndex,
                rawCounter = currentRawCounter,
                sensorEverReported = before.sensorEverReported ||
                    currentRawCounter != null,
            )
        } else before

        if (hcStepsToday == null) return result(base, Event.HC_NO_CHANGE)
        val higher = hcStepsToday > base.stepsToday
        val next = base.copy(
            stepsToday = maxOf(base.stepsToday, hcStepsToday),
            hasDailyEvidence = true,
            rawCounter = base.rawCounter ?: currentRawCounter,
            sensorEverReported = base.sensorEverReported ||
                currentRawCounter != null,
        )
        return result(next, if (higher) Event.HC_SEED else Event.HC_NO_CHANGE)
    }
}
