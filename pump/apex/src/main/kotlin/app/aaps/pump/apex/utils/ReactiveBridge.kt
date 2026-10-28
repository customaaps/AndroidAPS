package app.aaps.pump.apex.utils

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asObservable

/** Keep the transport's disposable lifecycle while consuming the current Flow-based core. */
fun <T : Event> RxBus.toObservable(type: Class<T>): Observable<T> = Observable.create { emitter ->
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    emitter.setCancellable { scope.cancel() }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            toFlow(type.kotlin).collect { if (!emitter.isDisposed) emitter.onNext(it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitter.tryOnError(error)
        }
    }
}

data class ApexPreferenceChange(val changedKey: String)

fun apexPreferenceChanges(preferences: Preferences): Observable<ApexPreferenceChange> {
    val flows: List<Flow<ApexPreferenceChange>> =
        ApexBooleanKey.entries.map { key -> preferences.observe(key).drop(1).map { ApexPreferenceChange(key.key) } } +
        ApexDoubleKey.entries.map { key -> preferences.observe(key).drop(1).map { ApexPreferenceChange(key.key) } } +
        ApexStringKey.entries.map { key -> preferences.observe(key).drop(1).map { ApexPreferenceChange(key.key) } }
    return merge(*flows.toTypedArray()).asObservable(Dispatchers.IO)
}
