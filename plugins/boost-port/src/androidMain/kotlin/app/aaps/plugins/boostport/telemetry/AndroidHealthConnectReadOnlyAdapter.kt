package app.aaps.plugins.boostport.telemetry

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import java.time.Duration
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Explicit-call-only Android boundary for Boost's *passive* Health Connect port.
 *
 * The SDK reader itself never requests permissions, persists records or controls
 * insulin. It is shared by explicit diagnostics and the APS-owned backfill worker;
 * callers must already have user-granted read permission. Lifecycle and persistence
 * belong to the caller, not this adapter.
 *
 * SDK, read permission, and errors are distinguished from genuinely empty records.
 * To avoid silently treating incomplete history as a valid zero, pagination failure
 * returns Failed rather than a partial data set.
 */
internal class AndroidHealthConnectReadOnlyAdapter(private val context: Context) {
    sealed interface ReadOutcome<out T> {
        data class Data<T>(val items: List<T>) : ReadOutcome<T>
        data class Blocked(val access: HealthConnectReadPolicy.Access) : ReadOutcome<Nothing>
        data class Failed(val code: String) : ReadOutcome<Nothing>
    }

    private companion object {
        const val MAX_PAGES = 20
        const val HR_WINDOW_MS = 18L * 60L * 60_000L
        const val STEPS_WINDOW_MS = 28L * 24L * 60L * 60_000L
    }

    private fun clientOrNull(): HealthConnectClient? = try {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    } catch (_: Exception) {
        null
    }

    private fun validWindow(nowMs: Long, lookbackMs: Long, maxMs: Long): Boolean =
        nowMs > 0L && lookbackMs in 1L..maxMs

    /**
     * Fetch existing heart-rate samples without touching the AAPS HR database.
     * liveHrMinutes come from a separate read-only caller, to avoid duplicating
     * Garmin / Wear live minute samples when HC backfills delayed data.
     */
    suspend fun readHeartRate(
        nowMs: Long,
        lookbackMs: Long = HR_WINDOW_MS,
        liveHrMinutes: Set<Long> = emptySet(),
        preserveSourceSampleCadence: Boolean = false,
    ): ReadOutcome<HealthConnectReadPolicy.HeartRateSample> {
        if (!validWindow(nowMs, lookbackMs, HR_WINDOW_MS))
            return ReadOutcome.Failed("invalid-hr-window")
        val client = clientOrNull()
            ?: return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.SDK_UNAVAILABLE)
        return try {
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in
                client.permissionController.getGrantedPermissions()
            ) return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)

            val fromMs = (nowMs - lookbackMs).coerceAtLeast(0L)
            val range = TimeRangeFilter.between(
                Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(nowMs)
            )
            val result = mutableListOf<HealthConnectReadPolicy.HeartRateSample>()
            var token: String? = null
            val pageGuard = HealthConnectPageGuard(MAX_PAGES)
            repeat(MAX_PAGES) {
                val page = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = range,
                        pageToken = token,
                    )
                )
                for (record in page.records) {
                    val device = record.metadata.device?.let { device ->
                        listOfNotNull(device.manufacturer, device.model)
                            .joinToString(" ").trim().ifBlank { "HealthConnect" }
                    } ?: "HealthConnect"
                    for (sample in record.samples) {
                        result += HealthConnectReadPolicy.HeartRateSample(
                            device = device,
                            timestampMs = sample.time.toEpochMilli(),
                            beatsPerMinute = sample.beatsPerMinute.toDouble(),
                        )
                    }
                }
                token = page.pageToken
                when (pageGuard.accept(token)) {
                    HealthConnectPageGuard.Next.COMPLETE ->
                        return ReadOutcome.Data(
                            if (preserveSourceSampleCadence) result.asSequence()
                                .filter { it.timestampMs in fromMs..nowMs && it.beatsPerMinute.isFinite() &&
                                    it.beatsPerMinute > 0.0 && it.device.isNotBlank() && it.timestampMs / 60_000L !in liveHrMinutes }
                                .distinctBy { it.timestampMs }.toList()
                            else HealthConnectReadPolicy.heartRateBackfillCandidates(result, liveHrMinutes, nowMs, lookbackMs)
                        )
                    HealthConnectPageGuard.Next.REPEATED_TOKEN ->
                        return ReadOutcome.Failed("hr-pagination-repeat")
                    HealthConnectPageGuard.Next.PAGE_LIMIT ->
                        return ReadOutcome.Failed("hr-pagination-limit")
                    HealthConnectPageGuard.Next.CONTINUE -> Unit
                }
            }
            ReadOutcome.Failed("hr-pagination-limit")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            ReadOutcome.Failed("hr-read-failed")
        }
    }

    /**
     * Fetch source-tagged *raw* step intervals, without summing source apps.
     * Duplicate/overlapping time ranges are NOT aggregated into a baseline here.
     */
    suspend fun readStepIntervals(
        nowMs: Long,
        lookbackMs: Long = STEPS_WINDOW_MS,
    ): ReadOutcome<HealthConnectReadPolicy.StepInterval> {
        if (!validWindow(nowMs, lookbackMs, STEPS_WINDOW_MS))
            return ReadOutcome.Failed("invalid-steps-window")
        val client = clientOrNull()
            ?: return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.SDK_UNAVAILABLE)
        return try {
            if (HealthPermission.getReadPermission(StepsRecord::class) !in
                client.permissionController.getGrantedPermissions()
            ) return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)

            val fromMs = (nowMs - lookbackMs).coerceAtLeast(0L)
            val range = TimeRangeFilter.between(
                Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(nowMs)
            )
            val result = mutableListOf<HealthConnectReadPolicy.StepInterval>()
            var token: String? = null
            val pageGuard = HealthConnectPageGuard(MAX_PAGES)
            repeat(MAX_PAGES) {
                val page = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = range,
                        pageToken = token,
                    )
                )
                for (record in page.records) {
                    result += HealthConnectReadPolicy.StepInterval(
                        dataOrigin = record.metadata.dataOrigin.packageName,
                        startMs = record.startTime.toEpochMilli(),
                        endMs = record.endTime.toEpochMilli(),
                        stepCount = record.count,
                        phoneDevice = record.metadata.device?.type == androidx.health.connect.client.records.metadata.Device.TYPE_PHONE,
                    )
                }
                token = page.pageToken
                when (pageGuard.accept(token)) {
                    HealthConnectPageGuard.Next.COMPLETE ->
                        return ReadOutcome.Data(
                            HealthConnectReadPolicy.stepIntervals(result, nowMs, lookbackMs)
                        )
                    HealthConnectPageGuard.Next.REPEATED_TOKEN ->
                        return ReadOutcome.Failed("steps-pagination-repeat")
                    HealthConnectPageGuard.Next.PAGE_LIMIT ->
                        return ReadOutcome.Failed("steps-pagination-limit")
                    HealthConnectPageGuard.Next.CONTINUE -> Unit
                }
            }
            ReadOutcome.Failed("steps-pagination-limit")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            ReadOutcome.Failed("steps-read-failed")
        }
    }
    /** Recent completed buckets for the live SC/graph feed, independent of the 28-day day ledger. */
    suspend fun readLiveStepBuckets(nowMs: Long): ReadOutcome<Map<Long, Long>> {
        val client = clientOrNull()
            ?: return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.SDK_UNAVAILABLE)
        return try {
            if (HealthPermission.getReadPermission(StepsRecord::class) !in
                client.permissionController.getGrantedPermissions()
            ) return ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)
            val end = nowMs / 300_000L * 300_000L
            if (end < 3 * 3_600_000L) return ReadOutcome.Failed("invalid-steps-window")
            val buckets = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(end - 3 * 3_600_000L), Instant.ofEpochMilli(end)),
                timeRangeSlicer = Duration.ofMinutes(5)))
            // The aggregate API deduplicates Activity sources using the user's HC priorities.
            // Null is missing data, not a measured zero.
            ReadOutcome.Data(listOf(buckets.mapNotNull { bucket ->
                bucket.result[StepsRecord.COUNT_TOTAL]?.let { bucket.endTime.toEpochMilli() to it }
            }.toMap()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ReadOutcome.Blocked(HealthConnectReadPolicy.Access.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            ReadOutcome.Failed("live-steps-read-failed")
        }
    }
}

