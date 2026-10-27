package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.boostport.BoostReplayStateKey

/** One sequential owner. Cache-first consistency mirrors source V5StateStore. */
internal class BoostReplayStateStore(
    private val storage: KeyValueStore,
    private val stateKey: String = BoostReplayStateKey.Session.key,
) {
    sealed interface Load {
        data class Ready(val snapshot: BoostReplayStateCodec.Snapshot) : Load
        data class Rejected(val reason: String) : Load
    }
    data class Save(val persisted: Boolean, val problem: String? = null)
    private var cached: BoostReplayStateCodec.Snapshot? = null

    fun load(): Load {
        cached?.let { return Load.Ready(it) }
        return try {
            val raw = storage.getString(stateKey, "")
            // Only the actual empty default denotes first use. Whitespace or an oversized
            // value is not evidence that an existing session can safely be discarded.
            val snapshot = if (raw.isEmpty()) BoostReplayStateCodec.Snapshot() else
                BoostReplayStateCodec.decode(raw) ?: return Load.Rejected("invalid-replay-state")
            cached = snapshot
            Load.Ready(snapshot)
        } catch (_: Exception) { Load.Rejected("state-read-failed") }
    }

    fun save(snapshot: BoostReplayStateCodec.Snapshot): Save {
        val owned = snapshot.copy(history = snapshot.history.toList())
        val raw = BoostReplayStateCodec.encode(owned) ?: return Save(false, "invalid-replay-state")
        // Keep the latest in-process state even if the backing store has delayed visibility
        // or its write fails. A write failure is returned explicitly, never called durable.
        cached = owned
        return try {
            storage.putString(stateKey, raw)
            Save(true)
        } catch (_: Exception) { Save(false, "state-write-failed") }
    }
}

/** Explicit-call replay lifecycle; no injection, registration, scheduling or command output. */
internal class BoostReplaySession(
    private val runner: BoostV1V5Replay,
    private val store: BoostReplayStateStore,
) {
    sealed interface Result {
        data class Calculated(val frame: BoostV1V5Replay.Frame, val persistence: BoostReplayStateStore.Save) : Result
        data class Rejected(val reason: String) : Result
    }
    private var restored = false

    fun run(cycle: V1ReplayCycle, context: V5ReplayContext, settings: V6ReplaySettings = V6ReplaySettings()): Result {
        val saved = when (val load = store.load()) {
            is BoostReplayStateStore.Load.Ready -> load.snapshot
            is BoostReplayStateStore.Load.Rejected -> return Result.Rejected(load.reason)
        }
        if (!restored) {
            runner.restoreFeatureHistoryOnce(saved.history)
            restored = true
        }
        val frame = runner.run(cycle, context, saved.v5, settings)
        val persistence = store.save(BoostReplayStateCodec.Snapshot(runner.featureHistory(), frame.nextV5State))
        return Result.Calculated(frame, persistence)
    }
}
