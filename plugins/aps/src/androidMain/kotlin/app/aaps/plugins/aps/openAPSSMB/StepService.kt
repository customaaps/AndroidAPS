package app.aaps.plugins.aps.openAPSSMB

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import kotlin.math.roundToLong

actual object StepService : SensorEventListener {

    private const val TAG = "StepService"
    private val lock = Any()
    private var previousStepCount = -1
    private val stepsMap = LinkedHashMap<Long, Int>()
    private const val fiveMinutesInMs = 300000

    // 36 five-minute buckets cover 180 minutes; keep a little extra so a rollover cannot truncate it.
    private const val numOf5MinBlocksToKeep = 40

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i(TAG, "onAccuracyChanged: Sensor: $sensor; accuracy: $accuracy")
    }

    private fun currentTimeIn5Min(): Long {
        return (System.currentTimeMillis() / fiveMinutesInMs.toDouble()).roundToLong()
    }

    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        sensorEvent ?: return

        val now = currentTimeIn5Min()
        val stepCount = sensorEvent.values[0].toInt()
        synchronized(lock) {
            if (previousStepCount >= 0) {
                // TYPE_STEP_COUNTER restarts at 0 after a reboot; a lower value is a counter reset,
                // not a negative number of steps.
                if (stepCount < previousStepCount) {
                    stepsMap.clear()
                    previousStepCount = stepCount
                    return
                }
                var recentStepCount = stepCount - previousStepCount
                if (stepsMap.containsKey(now)) {
                    recentStepCount += stepsMap.getValue(now)
                }
                stepsMap[now] = recentStepCount
            }
            previousStepCount = stepCount

            if (stepsMap.size > numOf5MinBlocksToKeep) {
                val removeBefore = now - numOf5MinBlocksToKeep
                stepsMap.entries.removeIf { it.key < removeBefore }
            }
        }
    }

    actual fun getRecentStepCount5Min(): Int = valueAt(currentTimeIn5Min() - 1)

    actual fun getRecentStepCount10Min(): Int = valueAt(currentTimeIn5Min() - 2)

    actual fun getRecentStepCount15Min(): Int = valueAt(currentTimeIn5Min() - 3)

    actual fun getRecentStepCount30Min(): Int = getStepsInLastXMin(6)

    actual fun getRecentStepCount60Min(): Int = getStepsInLastXMin(12)

    actual fun getRecentStepCount180Min(): Int = getStepsInLastXMin(36)

    private fun valueAt(bucket: Long): Int = synchronized(lock) { stepsMap[bucket] ?: 0 }

    private fun getStepsInLastXMin(numberOf5MinIncrements: Int): Int = synchronized(lock) {
        var stepCount = 0
        val cutoff = currentTimeIn5Min() - numberOf5MinIncrements
        for (entry in stepsMap.entries) {
            if (entry.key > cutoff) stepCount += entry.value
        }
        stepCount
    }

}
