package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/** Closing is serialized with each store operation, so an old cycle cannot overwrite an import. */
internal class BoostOwnerStorage(private val delegate: KeyValueStore) : KeyValueStore {
    private val lock = AapsLock()
    private var closed = false
    private var activeJob: Job? = null
    fun close(): Boolean = lock.withLock {
        if (closed) false else { closed = true; activeJob?.cancel(); true }
    }
    /** The caller serializes cycles. Close/import cancels pending suspend DB effects too. */
    suspend fun <T> cycle(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        lock.withLock { check(!closed) { "Boost calculation owner closed" }; activeJob = job }
        try { block() } finally { lock.withLock { if (activeJob === job) activeJob = null } }
    }
    private fun <T> access(block: () -> T): T = lock.withLock {
        check(!closed) { "Boost calculation owner closed" }
        block()
    }
    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) = access { delegate.edit(commit, block) }
    override fun getAll(): Map<String, *> = access { delegate.getAll() }
    override fun clear() = access { delegate.clear() }
    override fun contains(key: String) = access { delegate.contains(key) }
    override fun remove(key: String) = access { delegate.remove(key) }
    override fun getString(key: String, defaultValue: String) = access { delegate.getString(key, defaultValue) }
    override fun getStringOrNull(key: String, defaultValue: String?) = access { delegate.getStringOrNull(key, defaultValue) }
    override fun getBoolean(key: String, defaultValue: Boolean) = access { delegate.getBoolean(key, defaultValue) }
    override fun getDouble(key: String, defaultValue: Double) = access { delegate.getDouble(key, defaultValue) }
    override fun getInt(key: String, defaultValue: Int) = access { delegate.getInt(key, defaultValue) }
    override fun getLong(key: String, defaultValue: Long) = access { delegate.getLong(key, defaultValue) }
    override fun putString(key: String, value: String) = access { delegate.putString(key, value) }
    override fun putBoolean(key: String, value: Boolean) = access { delegate.putBoolean(key, value) }
    override fun putDouble(key: String, value: Double) = access { delegate.putDouble(key, value) }
    override fun putInt(key: String, value: Int) = access { delegate.putInt(key, value) }
    override fun putLong(key: String, value: Long) = access { delegate.putLong(key, value) }
    override fun incInt(key: String) = access { delegate.incInt(key) }
    override fun incLong(key: String) = access { delegate.incLong(key) }
}
