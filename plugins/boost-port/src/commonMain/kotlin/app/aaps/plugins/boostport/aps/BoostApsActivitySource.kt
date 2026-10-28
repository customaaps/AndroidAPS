package app.aaps.plugins.boostport.aps

/** Optional platform data acquisition; absence is unknown phone activity, not measured inactivity. */
interface BoostApsActivitySource {
    data class Snapshot(val steps5: Int = 0, val steps15: Int = 0, val steps30: Int = 0,
        val steps60: Int = 0, val stepsToday: Int = 0, val phoneFeedLive: Boolean = false)
    interface Lease {
        fun snapshot(nowMs: Long, offsetMinutes: Int): Snapshot
        /** Source-style asynchronous backfill; close cancels the owned worker. */
        fun healthConnect(enabled: Boolean, nowMs: Long, pollMinutes: Int)
        fun stepHistory(enabled: Boolean, nowMs: Long, offsetMinutes: Int) {}
        fun stepHistorySnapshot(nowMs: Long, offsetMinutes: Int): BoostStepHistorySnapshot? = null
        fun close()
    }
    fun requestPermissions() {}
    fun open(): Lease
}

interface BoostHeartRateReader {
    data class Sample(val timestampMs: Long, val bpm: Double, val device: String)
    sealed interface Result {
        data class Ready(val samples: List<Sample>) : Result
        data class Unavailable(val reason: String) : Result
    }
    suspend fun read(nowMs: Long, coveredMinutes: Set<Long>): Result
}

