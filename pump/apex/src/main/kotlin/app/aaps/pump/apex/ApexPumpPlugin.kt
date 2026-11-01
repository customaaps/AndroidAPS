package app.aaps.pump.apex

import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.constraints.PumpPluginConstraints
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.comment
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withEntriesProvider
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.apex.ui.ApexComposeContent
import app.aaps.pump.apex.ui.IcPluginApex
import app.aaps.pump.apex.utils.toObservable
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.IntKey as MetroIntKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import app.aaps.core.data.model.BS
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.misc.ApexNotificationCode as Notification
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.waitMillis
import app.aaps.pump.apex.connectivity.ApexBluetooth
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.BolusDeliverySpeed
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.pump.apex.utils.toApexReadableProfile
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.abs
import kotlin.math.min
import app.aaps.core.ui.R as CoreUiR

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
// Must be a singleton, like every other pump plugin: ApexService injects this class directly, and
// unscoped it would receive its own copy while the loop talks to the one in the plugin list. The
// service writes the pump-reported limits onto [pumpDescription] (see `updatePumpDescription`),
// which is a per-instance object - on a second copy those writes are invisible to the temp-basal
// and basal-rate constraints that read them.
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@PumpDriver
@MetroIntKey(1140)
@SingleIn(AppScope::class)
@Inject
class ApexPumpPlugin(
    aapsLogger: AAPSLogger,
    override val rh: ResourceHelper,
    commandQueue: CommandQueue,
    val context: Context,
    preferences: Preferences,
    val rxBus: RxBus,
    val aapsSchedulers: AapsSchedulers,
    val fabricPrivacy: FabricPrivacy,
    val dateUtil: DateUtil,
    val pump: ApexPump,
    val config: Config,
    val driverStatus: ApexDriverStatus,
    val concentrationHelper: app.aaps.core.interfaces.insulin.ConcentrationHelper,
    val userEntryLogger: app.aaps.core.interfaces.logging.UserEntryLogger,
    notificationManager: NotificationManager,
    private val apexNotifications: ApexNotifications
): PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .icon(IcPluginApex)
        .pluginName(TextRef.AndroidRes(R.string.apex_plugin_name))
        .description(TextRef.AndroidRes(R.string.apex_plugin_description))
        .showInList { false }
        .composeContent { plugin -> ApexComposeContent(plugin as ApexPumpPlugin) },
    ApexBooleanKey.entries + ApexDoubleKey.entries + ApexStringKey.entries,
    aapsLogger,
    rh,
    preferences,
    commandQueue,
    notificationManager
), Pump, PluginConstraints, PumpPluginConstraints {


    val hideSerial get() = preferences.observe(ApexBooleanKey.HideSerial)

    private val disposable = CompositeDisposable()

    private val serviceBound = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var service: ApexService? = null

    private fun unbindService() {
        if (serviceBound.compareAndSet(true, false)) context.unbindService(connection)
        service = null
    }
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            service = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as ApexService.LocalBinder
            this@ApexPumpPlugin.service = mLocalBinder.serviceInstance?.also {
                it.startConnection()
            }
        }
    }


    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "Starting APEX plugin")
        serviceBound.set(context.bindService(Intent(context, ApexService::class.java), connection, Context.BIND_AUTO_CREATE))
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ unbindService() }, fabricPrivacy::logException)
        // Bridge the pump model's data-change event to the state exposed to AAPS-core
        disposable += rxBus
            .toObservable(EventApexPumpDataChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ refreshReactiveState() }, fabricPrivacy::logException)
        refreshReactiveState()
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "Stopping APEX plugin")
        service?.disconnect()
        unbindService()
        disposable.clear()
        super.onStop()
    }

    private val dataTime = MutableStateFlow(0L)
    override val lastDataTime: StateFlow<Long> = dataTime.asStateFlow()
    private val bolusTime = MutableStateFlow<Long?>(null)
    override val lastBolusTime: StateFlow<Long?> = bolusTime.asStateFlow()
    private val bolusAmount = MutableStateFlow<PumpInsulin?>(null)
    override val lastBolusAmount: StateFlow<PumpInsulin?> = bolusAmount.asStateFlow()
    private val reservoir = MutableStateFlow(PumpInsulin(0.0))
    override val reservoirLevel: StateFlow<PumpInsulin> = reservoir.asStateFlow()
    private val battery = MutableStateFlow<Int?>(null)
    override val batteryLevel: StateFlow<Int?> = battery.asStateFlow()

    /**
     * Push the current [pump] model snapshot into the state observed by AAPS-core.
     *
     * Visible for tests so what this publishes can be checked without [onStart], which needs a
     * Handler on a real Looper.
     */
    @VisibleForTesting
    internal fun refreshReactiveState() {
        // The moment the phone applied the frame, not the pump's own clock reading inside it. This is
        // "when did we last hear from the pump", which is a phone-clock question, and the pump is the
        // wrong source for it twice over: its RTC reports whole minutes - the seconds byte was zero in
        // 125 of 128 frames - and it drifts against the phone, measured at 4 to 13 seconds behind
        // depending on how long ago the clocks were synced. Together that made this value 124 s stale
        // at the median and up to 193 s at the worst, for a frame that had just arrived.
        // Still 0 before the first frame of a pump, exactly as the pump clock was.
        dataTime.value = pump.lastStatusReceivedAt
        bolusTime.value = pump.lastBolus?.dateTime?.millis
        bolusAmount.value = pump.lastBolus?.let { PumpInsulin((it.standardPerformed + it.extendedPerformed) * 0.025) }
        reservoir.value = PumpInsulin(pump.reservoirLevel)
        battery.value = pump.batteryPercentageOrNull
    }

    override val isFakingTempsByExtendedBoluses = false
    override fun canHandleDST() = false

    override fun manufacturer() = ManufacturerType.Apex
    override fun model() = PumpType.APEX_TRUCARE_III
    override fun serialNumber() = preferences.get(ApexStringKey.LastConnectedSerialNumber)

    /** An eight-digit serial is required before the queue may attempt a connection. */
    override fun isConfigured(): Boolean = ApexDriverLogic.isSerialConfigured(preferences.get(ApexStringKey.SerialNumber))

    override val baseBasalRate: PumpRate
        get() = PumpRate(pump.basal?.rate ?: 0.0)
    override val pumpDescription = PumpDescription().fillFor(model())

    override fun isBusy() = false //service?.isBusy ?: false
    override fun isSuspended() = pump.isSuspended
    override fun isInitialized() = isConfigured() && pump.isInitialized && service != null
    override fun isConnecting() = service?.connectionStatus == ApexBluetooth.Status.CONNECTING
    override fun isHandshakeInProgress() =
        service?.connectionStatus == ApexBluetooth.Status.CONNECTED && service?.isReadyForExecutingCommands != true
    override fun isConnected() = service?.connectionStatus == ApexBluetooth.Status.CONNECTED && service?.isReadyForExecutingCommands == true
    override fun isBatteryChangeLoggingEnabled() = preferences.get(ApexBooleanKey.LogBatteryChange)

    /**
     * Every key this driver keeps out of a settings export, and so every key an import would otherwise
     * erase. `exportable = false` keeps a value out of the FILE; it does not protect it from the
     * `sp.clear()` that runs before the file is applied. This answers the question left open in
     * [ApexStringKey.LastConnectedSerialNumber]: the values are put back, because all four describe the
     * pump this phone is actually talking to, and none of them can travel in a file to begin with.
     *
     * Restoring is right on a second phone too. The file carries none of these keys, so what comes back
     * is that phone's own state, not another phone's. A restored address cannot get stuck either: it is
     * dropped on a serial change, on a mismatch at connect, and after three connections that never
     * identified as a pump. A restored [ApexStringKey.PendingBolusSync] is likewise either recent, and
     * then matching it is the whole point, or stale, and `ApexService` drops it unread.
     *
     * Read from the enum instead of listed here, so a new non-exportable key is covered the day it is
     * added rather than the day someone notices.
     */
    private val stateKeptAcrossImport: List<ApexStringKey> = ApexStringKey.entries.filter { !it.exportable }

    private var stateBeforeImport: Map<ApexStringKey, String>? = null

    override fun beforeImport() {
        stateBeforeImport = stateKeptAcrossImport.associateWith { preferences.get(it) }
        // No values. They are this pump's address and serial, and a bolus still in flight; the count
        // is all that is needed to tell a restore that ran from one that did not.
        aapsLogger.debug(LTag.PUMP, "Saving ${stateKeptAcrossImport.size} driver state values before settings import")
    }

    override fun afterImport() {
        val saved = stateBeforeImport ?: run {
            // afterImport without a beforeImport is not a case this driver can repair: the values are
            // already gone, and there is nothing to put back.
            aapsLogger.warn(LTag.PUMP, "No driver state was saved before the settings import, nothing to restore")
            return
        }
        // Written back exactly as read, empty values included, so the driver ends up in the state it was
        // in rather than in a mix of that state and the enum defaults.
        saved.forEach { (key, value) -> preferences.put(key, value) }
        stateBeforeImport = null
        aapsLogger.debug(LTag.PUMP, "Restored ${saved.size} driver state values after settings import")
    }

    // We should be always connected to the pump.
    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered connect: $reason")
        // A pump that refused this connection password stays quiet until the user changes it; retrying
        // from KeepAlive would only hammer it. An unbound service is not a lockout - that is the null case.
        if (service?.isPasswordLockout() == true) return
        if (service?.apexBluetooth?.status == ApexBluetooth.Status.DISCONNECTED) {
            service?.startConnection()
        }
    }
    /**
     * Zero, because [disconnect] does nothing: this driver stays connected on purpose, so
     * [isConnected] never turns false after a command. The core waits this many seconds for the
     * disconnect to happen and holds a partial wake lock the whole time, so any non-zero value here
     * is pure battery cost for a state change that can never arrive.
     */
    override fun waitForDisconnectionInSeconds(): Int = 0

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered disconnect: $reason")
    }
    override fun stopConnecting() {
        aapsLogger.debug(LTag.PUMP, "Triggered stopConnecting")
    }



    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        if (!isInitialized() || !isConnected()) return rh.gs(app.aaps.pump.common.R.string.pump_status_not_initialized)
        val status = pump.status!!

        val ret = "${rh.gs(R.string.status_conn_status)}: ${service!!.connectionStatus.toLocalString(rh)}\n" +
            "${rh.gs(R.string.status_pump_status)}: ${status.getPumpStatus(rh)}\n" +
            "${rh.gs(R.string.status_last_bolus)}: ${pump.lastBolus?.toShortLocalString(rh) ?: "-"}\n" +
            "${rh.gs(R.string.status_tbr)}: ${status.getTBR(rh)}\n" +
            "${rh.gs(R.string.status_basal)}: ${status.getBasal(rh)}\n" +
            "${rh.gs(R.string.status_reservoir)}: ${status.getReservoirLevel(rh)}\n" +
            "${rh.gs(R.string.status_today_delivered)}: ${status.getTodayDelivered(rh)}\n" +
            "${rh.gs(R.string.status_battery)}: ${status.getBatteryLevel(rh)}"

        aapsLogger.debug(LTag.PUMP, "Short status: $ret")
        return ret
    }

    fun updatePumpDescription() {
        val maxBasal = pump.maxBasal
        // Before this plugin became a @SingleIn(AppScope::class) the write landed on a second, unread instance, so a frame
        // reporting no limit at all was harmless. It is load-bearing now and must not be allowed to pin
        // every temp basal at 0 U/h until the next good status arrives.
        if (maxBasal <= 0.0) {
            aapsLogger.warn(LTag.PUMP, "Not updating pump description - pump reported max basal $maxBasal")
            return
        }
        aapsLogger.debug(LTag.PUMP, "Updating pump description - max temp basal $maxBasal U/h")
        // Only the temp basal ceiling. basalMaximumRate is deliberately left at the pump type default:
        // the profile treats a basal rate above it as invalid rather than merely clamped, so a profile
        // switch would be refused with no message shown. The pump's max basal is a user-editable pump menu
        // setting, not a hardware limit, and applyBasalConstraints already clamps what is actually sent.
        pumpDescription.maxTempAbsolute = maxBasal
    }

    override suspend fun loadTDDs(): PumpEnactResult {
        val ret = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        val status = service!!.getTDDs("ApexPumpPlugin-loadTDDs")
        return ret.apply {
            success = status
            enacted = status
        }
    }

    override suspend fun getPumpStatus(reason: String) {
        if (!isInitialized() || !isConnected()) return
        aapsLogger.debug(LTag.PUMP, "Requested pump status cause of $reason")
        if (!service!!.getStatus("ApexPumpPlugin-getPumpStatus")) return
    }

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        val ret = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        // Refuse a profile the pump cannot deliver, instead of writing it and hoping.
        //
        // The 96-byte rates command carries no limit of its own, and what the firmware does with a rate
        // above its own Max Basal has never been observed: it may reject the whole write, clip the one
        // slot, or take it. A silent clip is the dangerous one - AAPS would believe it had programmed a
        // profile the pump is not running. The temp-basal path is already guarded by
        // applyBasalConstraints; the profile write is a separate path and was not.
        val rates = profile.toApexReadableProfile()
        val pumpMaxBasal = pump.maxBasal
        val peak = rates.maxOrNull() ?: 0.0
        if (pumpMaxBasal > 0.0 && peak > pumpMaxBasal) {
            aapsLogger.error(
                LTag.PUMP,
                "Not writing the basal profile - its peak ${peak}U/h is above the pump's own max basal ${pumpMaxBasal}U/h"
            )
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_basal_profile_above_pump_max, peak, pumpMaxBasal)
            }
        }

        // The rates command writes the pattern the pump is CURRENTLY using. Normally that is
        // already pattern 7 (onSettingsChanged keeps it there), so the reference driver's
        // write-then-select order is kept. On any other pattern (fresh pairing, or a keypad
        // change no status frame has healed yet) that order put the new rates into the WRONG
        // pattern - selecting 7 afterwards does not move them - so there the index is selected
        // first and the write then lands in 7.
        val onUsedPattern = pump.currentBasalPattern == ApexService.USED_BASAL_PATTERN_INDEX
        if (!onUsedPattern) {
            val switched = service!!.updateBasalPatternIndex(ApexService.USED_BASAL_PATTERN_INDEX, "ApexPumpPlugin-setNewBasalProfile")
            if (!switched) {
                return ret.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.error_failed_to_switch_basal_profile_index)
                }
            }
        }

        var result = service!!.updateCurrentBasalPattern(rates, "ApexPumpPlugin-setNewBasalProfile", targetsUsedPattern = !onUsedPattern)
        if (!result) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_failed_to_update_basal_profile)
            }
        }

        if (onUsedPattern) {
            result = service!!.updateBasalPatternIndex(ApexService.USED_BASAL_PATTERN_INDEX, "ApexPumpPlugin-setNewBasalProfile")
            if (!result) {
                return ret.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.error_failed_to_switch_basal_profile_index)
                }
            }
        }

        return ret.apply {
            success = true
            enacted = true
        }
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (!isInitialized() || !isConnected()) return true
        // Compare the last rates we read or wrote. A live BLE read here ran from KeepAlive every
        // few minutes, held the command lock for up to 30 s, and on a timeout returned true —
        // AAPS then skipped the profile write and left the pump on the old rates.
        val pumpBasalProfile = pump.cachedBasalProfile ?: return false
        val requested = profile.toApexReadableProfile()
        if (!ApexDriverLogic.basalProfileMatches(requested, pumpBasalProfile)) {
            for (i in 0 until 48) {
                if (abs(pumpBasalProfile[i] - requested[i]) > DoseStepSize.Apex.getStepSizeForAmount(requested[i]) / 2) {
                    aapsLogger.info(LTag.PUMP, "Profiles are not same: req ${requested[i]} != pump ${pumpBasalProfile[i]}, block $i, time ${i * 30 * 60}")
                    break
                }
            }
            return false
        }
        return true
    }

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Insulin value must be greater than 0
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }
        val pumpEnactResult = PumpEnactResultImpl()
        val requested = detailedBolusInfo.insulin
        // The queue constrains IU; PumpWithConcentration applies pump limits in cU before this call.
        // Single self-sufficient line per bolus request: without it an exported log shows the pump
        // traffic but not what AAPS actually asked for, which is the first thing needed to explain a
        // suspected mis-dose.
        aapsLogger.info(
            LTag.PUMP,
            "deliverTreatment: requested ${requested}U -> after constraints ${detailedBolusInfo.insulin}U, " +
                "type=${detailedBolusInfo.bolusType}, ts=${detailedBolusInfo.timestamp}, " +
                "initialized=${isInitialized()}, connected=${isConnected()}, suspended=${isSuspended()}"
        )

        if (!isInitialized() || !isConnected()) {
            aapsLogger.warn(LTag.PUMP, "deliverTreatment: refused - pump not ready")
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            aapsLogger.warn(LTag.PUMP, "deliverTreatment: refused - pump suspended")
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val result = service!!.bolus(detailedBolusInfo, "ApexPumpPlugin-deliverTreatment")
        if (!result) {
            aapsLogger.warn(LTag.PUMP, "deliverTreatment: pump did not start the bolus")
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_bolus_start_failed)
            }
        }

        // How long the pump can still plausibly be delivering. The arithmetic lives in ApexDriverLogic
        // because the bolus watchdog needs the same estimate, and two copies would drift apart.
        val maxReasonableBolusTimeMs = ApexDriverLogic.bolusDeliveryMs(
            insulin = detailedBolusInfo.insulin,
            lowSpeed = preferences.get(ApexStringKey.BolusSpeed) == BolusDeliverySpeed.Low.name,
        )
        // Capture the in-progress bolus reference *before* waiting. When a bolus completes (or
        // entirely fails), the service thread finalizes currentDose/failed, calls notifyAll(), then
        // ~100 ms later sets pump.inProgressBolus = null. Re-reading the field after we wake races
        // that null-out and can report a fully delivered+synced bolus as failed. The captured object
        // outlives the field reset and its outcome fields are final once we are woken.
        val bolus = pump.inProgressBolus
        bolus?.let {
            synchronized(it) {
                it.waitMillis(maxReasonableBolusTimeMs)
            }
        }

        val successful = bolus != null && !bolus.failed
        // The wait above ends either because the pump's own history entry was matched, or because the
        // longest a bolus of this size could plausibly take has run out. Only the first is proof. The
        // second is not proof of failure either - the insulin may have gone in with the confirmation
        // lost on a dropped link - but it is not the delivered bolus that was asked for, and until now
        // it was reported as exactly that: `failed` is set only when no progress at all was reported,
        // so a bolus that stopped halfway, or one whose confirmation never arrived, came back as a
        // plain success.
        //
        // Deliberately still a success to the caller. A bolus reported as failed makes LoopPlugin
        // re-run the loop a second later, and a repeat is the one outcome that can do harm on a lost
        // acknowledgement. So the loop is left exactly as it was and the user is told instead - the
        // same answer the Omnipod Dash driver gives to the same problem.
        //
        // The `failed` half of that result is NOT this change's to correct: a bolus the pump ended with
        // no progress reported is still handed back as a failure even when nothing proves the motor
        // stopped, which on that ending tells the user "it did not go in" about a dose nobody can
        // account for. `refreshUnconfirmedWarning` now raises its card there, so the warning is at
        // least present; making the reported RESULT honest as well would change what the bolus wizard
        // shows on a path this change did not otherwise touch, and that is a decision to take on its
        // own, with the pump in hand.
        aapsLogger.info(
            LTag.PUMP,
            "deliverTreatment: finished - requested ${detailedBolusInfo.insulin}U, delivered ${bolus?.currentDose}U, " +
                "failed=${bolus?.failed}, cancelled=${bolus?.cancelled}, tempId=${bolus?.temporaryId}, " +
                "waited up to ${maxReasonableBolusTimeMs / 1000}s, success=$successful"
        )
        bolus?.let { refreshUnconfirmedWarning(it) }
        return pumpEnactResult.apply {
            success = successful
            if (successful) {
                enacted = bolus.currentDose > 0.024
                bolusDelivered = bolus.currentDose
            }
        }
    }

    /**
     * Bring the "this bolus may be incomplete" card in line with what is known about [bolus] right now.
     *
     * The single owner of that card, and the reason it is a function rather than two pieces of code:
     * two threads learn about the same bolus at different times. `deliverTreatment` learns that its
     * wait ran out; `ApexService` learns, later, what the pump's own history entry says. Both call
     * this, both under the bolus monitor, so the card ends up describing the newest numbers instead of
     * whichever thread happened to run last, and neither has to know what the other did.
     *
     * The rules are the same whoever calls:
     *  - the bolus is whole, or was cancelled, or failed outright: there is nothing uncertain left to
     *    say, so any card raised for it comes down.
     *  - it is short: say so, with the amounts as they are known now. A card that already names those
     *    same amounts is left alone, so a second look at the same pump entry does not ring again.
     *
     * Runs under the monitor because the alternative was a race: the waiter used to compose and post
     * the card outside any lock and only then store its handle, so the thread that had just proved the
     * bolus whole could look for a card that was still being posted, find nothing, and leave a warning
     * standing that the pump had already disproved.
     *
     * One rule of the reactive version is gone: it also asked whether the user had swiped the card
     * away, and let go of it if so. This version has no read side to the notification store, so the
     * card is treated as still standing. A swiped card can therefore come back when the pump reports a
     * different amount - the safe direction, and the only one available: answering "the user swiped
     * it" instead would drop the handle on every call and leave a settled bolus's warning on screen
     * for good.
     */
    fun refreshUnconfirmedWarning(bolus: ApexPump.InProgressBolus) {
        synchronized(bolus) {
            val requested = bolus.requestedDose
            val confirmed = bolus.currentDose
            val short = ApexDriverLogic.isBolusShortfall(
                requested = requested,
                delivered = confirmed,
                cancelled = bolus.cancelled,
                // Both halves. A bolus that ended with nothing reported is only settled when the pump
                // also proved the motor stopped; ended on an alarm that says nothing about the motor,
                // it is the least accounted-for dose there is, and the one most worth a card.
                provenFailure = bolus.failed && bolus.deliveryStopProven,
                bolusStep = pumpDescription.bolusStep,
            )
            val standing = bolus.unconfirmedWarning
            if (!short) {
                standing?.let {
                    aapsLogger.debug(LTag.PUMP, "Bolus settled at ${confirmed}U of ${requested}U - taking the unconfirmed-bolus card down")
                    apexNotifications.dismiss(it)
                }
                bolus.unconfirmedWarning = null
                bolus.unconfirmedWarningDose = null
                return
            }
            // Same card, same numbers: nothing new to tell, and re-posting would start the alarm ramp
            // again from the beginning.
            if (standing != null && bolus.unconfirmedWarningDose == confirmed) return
            // The pump has since reported a different amount, so the card on screen names a figure the
            // pump has superseded. Replace it rather than leave a wrong number on a dosing warning.
            standing?.let { apexNotifications.dismiss(it) }
            bolus.unconfirmedWarning = warnBolusUnconfirmed(requested, confirmed, bolus.detailedBolusInfo.bolusType)
            bolus.unconfirmedWarningDose = confirmed
        }
    }

    /**
     * Settle the card of a bolus whose live record was dropped, now that the pump has said how much of
     * it went in.
     *
     * The record is no longer the one the driver doses from - it is kept only because it carries the
     * handle of the card. [confirmedDose] is the pump's own figure out of its history entry, which is
     * the answer the card was waiting for, and it replaces whatever the wait had ended on.
     *
     * The failure flag goes with it: it was set when the bolus was abandoned, and the pump has now said
     * insulin WAS delivered, which is the opposite of the outright failure that flag recorded. Leaving
     * it set would make the card look settled when it is not.
     */
    fun settleAbandonedWarning(bolus: ApexPump.InProgressBolus, confirmedDose: Double) {
        synchronized(bolus) {
            bolus.currentDose = confirmedDose
            bolus.failed = false
        }
        refreshUnconfirmedWarning(bolus)
    }

    /**
     * Say that a bolus may be short, loudly enough for the amount to matter and quietly enough for an
     * SMB not to become noise.
     *
     * A hand-given bolus is urgent and audible: somebody is standing there expecting insulin, and the
     * entry dialog is long gone by the time this is known. An SMB is normal and silent: they are
     * small and frequent, and the loop corrects itself at the next reading.
     *
     * The two kinds get a card each. On this version a notification id IS the card, so sharing one id
     * would mean the quiet SMB line replaces the loud card about a hand-given bolus somebody is
     * standing there waiting for. A run of SMBs on a bad link still cannot bury the screen: they all
     * land on the one SMB id and keep replacing each other.
     *
     * @return the handle of the card.
     */
    @VisibleForTesting
    fun warnBolusUnconfirmed(requested: Double, confirmed: Double, bolusType: BS.Type): ApexNotifications.Handle {
        val isSMB = bolusType == BS.Type.SMB
        return apexNotifications.post(
            if (isSMB) ApexNotifications.BOLUS_UNCONFIRMED_SMB else ApexNotifications.BOLUS_UNCONFIRMED,
            rh.gs(
                R.string.bolus_unconfirmed,
                rh.gs(app.aaps.core.interfaces.InterfacesStrings.format_insulin_units, requested),
                rh.gs(app.aaps.core.interfaces.InterfacesStrings.format_insulin_units, confirmed)
            ),
            level = if (isSMB) Notification.NORMAL else Notification.URGENT,
            soundRes = if (isSMB) null else CoreUiR.raw.boluserror
        )
    }

    @Synchronized
    override fun stopBolusDelivering() {
        if (!isInitialized() || !isConnected()) return
        service!!.cancelBolus("ApexPumpPlugin-stopBolusDelivering")
    }

    override fun applyBasalConstraints(absoluteRate: PumpRate): PumpRate =
        if (pump.maxBasal > 0.0) PumpRate(min(absoluteRate.cU, pump.maxBasal)) else absoluteRate

    override fun applyBolusConstraints(insulin: PumpInsulin): PumpInsulin =
        if (pump.maxBolus > 0.0) PumpInsulin(min(insulin.cU, pump.maxBolus)) else insulin

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        val pumpEnactResult = PumpEnactResultImpl()
        // Basal constraints are applied by the loop, and by the command queue for every other caller,
        // through applyBasalConstraints() above; re-clamp defensively to the pump max basal (mirrors
        // the reference driver).
        val rate = if (pump.maxBasal > 0.0) min(absoluteRate, pump.maxBasal) else absoluteRate
        val duration = ApexDriverLogic.alignedTbrDurationMinutes(durationInMinutes)
        aapsLogger.info(
            LTag.PUMP,
            "setTempBasalAbsolute: requested ${absoluteRate}U/h for ${durationInMinutes}min -> " +
                "sending ${rate}U/h for ${duration ?: 0}min (pump max ${pump.maxBasal}U/h), " +
                "enforceNew=$enforceNew, type=$tbrType, running TBR=${pump.tbr?.rate}"
        )

        if (duration == null) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_duration_too_short)
            }
        }

        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = pump.status!!
        if (enforceNew && status.tbr != null) {
            val result = service!!.cancelTemporaryBasal("ApexPumpPlugin-setTempBasal")
            if (!result) {
                return pumpEnactResult.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.error_tbr_cancel_failed)
                }
            }
        }

        val result = service!!.temporaryBasal(rate, duration, tbrType, "ApexPumpPlugin-setTempBasal")
        if (!result) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_set_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    override suspend fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_only_absolute_supported)
        }
    }

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val pumpEnactResult = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        // [ApexPump.isBasalPaused], not isSuspended(): the two differ while the pump stands on an
        // alarm that stopped the motor. There isSuspended() is true on purpose, to keep the loop off
        // a pump that is not delivering - but a temporary basal recorded before the stop is still
        // crediting basal that no motor is running, and cancelling it is the one thing that makes
        // the record honest again. Core says the same: it allows a temp basal cancel and nothing else
        // while the pump has suspended delivery itself.
        if (pump.isBasalPaused) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = service!!.cancelTemporaryBasal("ApexPumpPlugin-cancelTempBasal")
        if (!status) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_cancel_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        // Not yet supported
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    override suspend fun cancelExtendedBolus(): PumpEnactResult {
        // Not yet supported
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        if (!isInitialized() || !isConnected()) return
        service!!.syncDateTime("ApexService-timezoneOrDSTChanged")
    }

    override fun hasPreferences(): Boolean = true

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "apex_settings",
        titleResId = R.string.apex_settings,
        icon = pluginDescription.icon,
        items = listOf(
            ApexStringKey.SerialNumber,
            ApexStringKey.FirmwareVer.withEntriesProvider(provider = {
                linkedMapOf<String, TextRef>(FirmwareVersion.AUTO.name to TextRef.AndroidRes(R.string.auto)).apply {
                    FirmwareVersion.realValues.filter { !it.engineeringModeOnly || config.isEngineeringMode() }
                        .forEach { put(it.name, TextRef.Literal(it.displayName)) }
                }
            }),
            ApexStringKey.AlarmSoundLength, ApexStringKey.BolusSpeed,
            ApexBooleanKey.CalculateBatteryPercentage, ApexStringKey.CalcBatteryType,
            ApexDoubleKey.BatteryLowVoltage, ApexDoubleKey.BatteryHighVoltage,
            ApexDoubleKey.MaxBasal, ApexDoubleKey.MaxBolus,
            ApexBooleanKey.LogInsulinChange, ApexBooleanKey.LogBatteryChange,
            ApexBooleanKey.HideSerial, ApexBooleanKey.ReadFillHistory,
            ApexBooleanKey.PumpHasPassword, ApexStringKey.PumpPassword
        )
    )

    private inner class PumpEnactResultImpl : PumpEnactResult {
        override var success: Boolean = false
        override var enacted: Boolean = false
        override var comment: String = ""
        override var duration: Int = 0
        override var absolute: Double = 0.0
        override var percent: Int = 0
        override var isPercent: Boolean = false
        override var isTempCancel: Boolean = false
        override var bolusDelivered: Double = 0.0
        override var queued: Boolean = false
        override var cancelled: Boolean = false

        override fun cancelled(cancelled: Boolean): PumpEnactResult = apply { this.cancelled = cancelled }
        override fun comment(ref: TextRef): PumpEnactResult = apply { this.comment = rh.gs(ref) }
        override fun success(success: Boolean): PumpEnactResult = apply { this.success = success }
        override fun enacted(enacted: Boolean): PumpEnactResult = apply { this.enacted = enacted }
        override fun comment(comment: String): PumpEnactResult = apply { this.comment = comment }
        fun comment(comment: Int): PumpEnactResult = apply { this.comment = rh.gs(comment) }
        override fun duration(duration: Int): PumpEnactResult = apply { this.duration = duration }
        override fun absolute(absolute: Double): PumpEnactResult = apply { this.absolute = absolute }
        override fun percent(percent: Int): PumpEnactResult = apply { this.percent = percent }
        override fun isPercent(isPercent: Boolean): PumpEnactResult = apply { this.isPercent = isPercent }
        override fun isTempCancel(isTempCancel: Boolean): PumpEnactResult = apply { this.isTempCancel = isTempCancel }
        override fun bolusDelivered(bolusDelivered: Double): PumpEnactResult = apply { this.bolusDelivered = bolusDelivered }
        override fun queued(queued: Boolean): PumpEnactResult = apply { this.queued = queued }

    }
}
