package app.aaps.workflow

/** Source InvokeLoopWorker policy, shared by all automatic APS loop invocations. */
internal object LoopCadence {
    const val MIN_FIVE_MINUTE_ADVANCE = 270_000L

    fun shouldTrigger(glucoseTimestamp: Long, lastTriggeredRun: Long, nativeCadence: Boolean): Boolean {
        if (glucoseTimestamp <= lastTriggeredRun) return false
        if (nativeCadence || lastTriggeredRun == 0L) return true
        return glucoseTimestamp - lastTriggeredRun >= MIN_FIVE_MINUTE_ADVANCE
    }
}
