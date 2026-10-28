package app.aaps.pump.apex

import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.misc.ApexNotificationCode as Notification
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.pump.apex.utils.apexPreferenceChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.core.utils.notifyAll
import app.aaps.core.utils.toHex
import app.aaps.core.utils.waitMillis
import app.aaps.pump.apex.connectivity.ApexBluetooth
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.CancelBolus
import app.aaps.pump.apex.connectivity.commands.device.CancelTemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.device.GetValue
import app.aaps.pump.apex.connectivity.commands.device.RequestHeartbeat
import app.aaps.pump.apex.connectivity.commands.device.SyncDateTime
import app.aaps.pump.apex.connectivity.commands.device.TemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.UpdateBasalProfileRates
import app.aaps.pump.apex.connectivity.commands.device.UpdateSystemState
import app.aaps.pump.apex.connectivity.commands.device.UpdateUsedBasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.AlarmObject
import app.aaps.pump.apex.connectivity.commands.pump.BasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.CommandResponse
import app.aaps.pump.apex.connectivity.commands.pump.FillEntry
import app.aaps.pump.apex.connectivity.commands.pump.Heartbeat
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObject
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.TDDEntry
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.toEnumOrNull
import app.aaps.pump.apex.utils.validateDateTime
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.connectivity.commands.pump.BolusDeliverySpeed
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.core.objects.workflow.MetroService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.lang.ref.WeakReference
import io.reactivex.rxjava3.kotlin.plusAssign
import org.joda.time.DateTime
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import dev.zacsweers.metro.Inject
import kotlin.concurrent.schedule
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
class ApexService: MetroService(), ApexBluetoothCallback {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var apexBluetooth: ApexBluetooth
    @Inject lateinit var apexDeviceInfo: ApexDeviceInfo
    @Inject lateinit var apexPumpPlugin: ApexPumpPlugin
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var pumpSync: ApexPumpSync
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var pump: ApexPump
    @Inject lateinit var config: Config
    @Inject lateinit var status: ApexDriverStatus
    @Inject lateinit var apexNotifications: ApexNotifications
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var bolusProgressData: BolusProgressData

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val COMMAND_RESPONSE_TIMEOUT = 5000L

        /**
         * The original driver waits 10 s for a single-value reply; the port halved it to 5 s. The
         * archived session shows why that matters: a StatusV1 request timed out at exactly 5.001 s and
         * the pump's answer arrived 48 ms later. Because the first thing `onConnect` reads is the
         * firmware version, a link slow enough to miss this window turns into a connect/disconnect loop.
         */
        const val SINGLE_VALUE_RESPONSE_TIMEOUT = 10000L
        const val COMPLEX_VALUE_RESPONSE_TIMEOUT = 30000L

        /**
         * How long the one-shot alarm-history diagnostic waits for the pump's FIRST record.
         *
         * The FIRST record only. Once records are arriving the read gets the ordinary
         * [COMPLEX_VALUE_RESPONSE_TIMEOUT], because a chunked read is released 500 ms after its last
         * frame, so a single short bound would cut the slowest healthy reads short: captured reads of
         * the ten-record bolus ring had a median of 901 ms but ran to 4458 ms, past this value.
         *
         * The short first bound exists because this read holds the command lock while the driver has
         * already told the queue it is ready, so an insulin command arriving in that window waits
         * behind it. A pump that answers this value answers its first frame in well under a second -
         * 154 ms in a capture of the bolus ring - and a pump that ignores it is what this is for.
         */
        const val ALARM_HISTORY_RESPONSE_TIMEOUT = 4000L

        /**
         * How long the fill-history read waits for its FIRST record. Shorter than the alarm history's
         * four seconds because this driver has never seen the pump answer this value at all: the more
         * likely outcome is silence, and silence must be cheap. A read that has started streaming is
         * not cut short by this - the bound is on silence, not on speed.
         */
        const val FILL_HISTORY_RESPONSE_TIMEOUT = 1500L

        /**
         * How long to wait for the pump's answer on FFC2 before giving up on it.
         *
         * The maker's own app waits not at all - it writes the password and starts its data sync in
         * the same handler tick - so there is no observed figure to copy and no reason to think the
         * pump is slow here: the answer is one byte with no work behind it. Three seconds is generous
         * against that and short enough that a pump which never answers costs one connection three
         * seconds, once, with the command queue idle.
         */
        const val PASSWORD_REPLY_TIMEOUT = 3000L

        /**
         * How long to wait for the FFC2 subscription to be confirmed before giving up on the whole
         * step.
         *
         * A CCCD write goes over the air and is answered by the PUMP - it is a round trip, not a local
         * call, which is the whole reason this wait exists. It is short because the round trip is
         * short: the handshake's own CCC write was answered in 40 ms in the 2026-09-03 capture, so two
         * seconds is about fifty times what this pump needs. It is also deliberately longer than the
         * 1500 ms write pacing, so the command lock is never released inside the driver's own pacing
         * window while a descriptor write is still outstanding.
         */
        const val PASSWORD_SUBSCRIBE_TIMEOUT = 2000L

        /**
         * How long to wait for the FFC1 password write to complete before giving up on the step.
         *
         * FFC1 is write-with-response, so this is a pump round trip like the subscribe, and short for
         * the same reason. Longer than the 1500 ms write pacing so the data channel is never probed
         * while this write is still outstanding.
         */
        const val PASSWORD_WRITE_TIMEOUT = 2000L

        /** How far back a bolus history entry is still worth syncing, matching the original driver. */
        const val HISTORY_RECENCY_WINDOW = 5 * 60 * 60 * 1000L

        const val USED_BASAL_PATTERN_INDEX = 7

        /**
         * How long the cached pattern-7 rates are trusted before the heartbeat re-reads them.
         * Rates edited on the pump keypad are otherwise invisible: no status field carries the
         * pattern contents, and [ApexPumpPlugin.isThisProfileSet] never talks to the pump.
         */
        const val BASAL_PROFILE_CACHE_MAX_AGE_MS = 60 * 60 * 1000L

        /** Back-off between failed cache-refresh reads, so a pump that stops answering them is not asked every heartbeat. */
        const val BASAL_PROFILE_REFRESH_RETRY_MS = 10 * 60 * 1000L

        /** Quantization grid for [pumpIdOf]; the raw dose occupies the remainder of the slot. */
        const val PUMP_ID_TIME_GRID_MS = 2000L

        /** Silence after the last frame that ends a chunked read. The pump sends no end marker. */
        const val CHUNKED_READ_IDLE_MS = 500L

        /** Tag the wake lock reports itself under, distinct from the command queue's own. */
        const val WAKE_LOCK_TAG = "AAPS:ApexService"

        /**
         * Backstop on how long the wake lock can stay held, re-armed on every acquire.
         *
         * Not a budget, and deliberately far above the longest episode. That episode is a heartbeat
         * cycle, not the handshake: it runs up to four reads - StatusV1 and StatusV2 at
         * [SINGLE_VALUE_RESPONSE_TIMEOUT], the bolus history and sometimes the basal pattern at
         * [COMPLEX_VALUE_RESPONSE_TIMEOUT] - which is already about 80 s of timeouts alone, and a
         * failed read can take the reconnect path, where `ensureConnected` waits up to 50 s more.
         * A backstop shorter than the work it covers would expire mid-episode, which is the failure
         * it exists to prevent.
         */
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** How often the pump's own insulin-on-board figure is compared with the one AAPS computes. */
        const val PUMP_IOB_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * Half a pump step: below this the stored pump limit is the same value as the reported one.
         *
         * Needed because the preference store keeps a Double as a Float, so a value read back is not
         * bit-identical to the one written.
         */
        const val PUMP_LIMIT_MATCH_U = 0.0125

        /**
         * Difference between the two insulin-on-board figures that is worth a warning.
         *
         * Roughly the smallest bolus worth noticing as missing or counted twice. A steady offset
         * smaller than this is expected: the two are computed from different models.
         */
        const val PUMP_IOB_DIVERGENCE_U = 0.5

        /**
         * How many pump ids the service remembers before starting over.
         *
         * Both id sets grow with real bolus activity only, never with re-reading the ring: a completed
         * entry carries a frozen delivered amount, so the ten entries that come back every two minutes
         * are recognised and add nothing. `offeredPumpIds` is keyed on the delivered amount as well as
         * the id, so a bolus read while it is still running adds one entry per distinct partial - still
         * a few hundred a day at worst, far below this. The limit exists only so neither set can grow
         * without bound if a service instance ever runs for months. Starting over costs one redundant
         * offer per entry still in the pump's ring.
         */
        const val SEEN_PUMP_IDS_LIMIT = 2000

        /**
         * Stable, collision-resistant pump id for a bolus history entry.
         *
         * The pump stamps every bolus at second 59 of its minute, so the raw timestamp is NOT unique:
         * two boluses inside one minute would share a pumpId and the sync transaction would merge the
         * second into the first, silently losing insulin from IOB. Quantizing the timestamp to a 2 s
         * grid and adding the *requested* raw dose separates two same-minute entries of different size
         * while staying identical across repeated reads of the same entry. The raw dose is bounded by
         * the pump's 25 U maximum at 0.025 U steps (= 1000) and so can never carry into the next slot.
         *
         * What this does NOT fix: two entries in the same minute with the SAME requested dose still
         * share an id, and the sync transaction still merges the second into the first. The dose term
         * narrows the window from a whole minute to nothing at all for different doses, but leaves the
         * identical-dose case open - and that case is the dangerous one, because the most likely way to
         * produce it is a user repeating a bolus that looked like it failed.
         *
         * That case also cannot be detected from a single entry, and `logPumpIdCollision` below does not
         * catch it: because the raw dose is smaller than the grid, two entries share an id only when
         * they fall in the same 2 s slot and carry the same requested dose - and the pump stamps every
         * entry at second 59 of its minute, so one slot only ever holds one stamp value. The identity
         * the detector compares is then equal too, which is exactly what would have to differ for a
         * collision to be recognisable. Telling the two apart needs something outside the entry -
         * the pump's own index within one read is the only candidate, and its meaning is not established
         * (its single reader treats it as a ring position, which shifts between reads), so it is
         * deliberately neither part of the id nor used as a discriminator.
         */
        @VisibleForTesting
        fun pumpIdOf(dateTimeMillis: Long, standardDose: Int): Long =
            (dateTimeMillis / PUMP_ID_TIME_GRID_MS) * PUMP_ID_TIME_GRID_MS + standardDose

        /**
         * Time to store for a bolus that only exists in pump history (given on the pump keypad).
         *
         * Because the pump stamps second 59 of the minute, its time is up to a minute LATER than the
         * delivery, and history is read within seconds - so the stamp regularly lands in the future.
         * A bolus dated in the future is left out of IOB until the clock catches up, and the loop then
         * doses as if that insulin did not exist. Pulling the value back to "now" is the smallest
         * correction that removes it: the read always happens after the delivery, so "now" is never
         * earlier than the real bolus, and a stamp that is already in the past is kept untouched.
         *
         * Only the stored time is corrected. The pump id keeps the raw pump time (see [pumpIdOf]),
         * because core deduplicates on that id and a changed id would insert the same bolus twice.
         */
        @VisibleForTesting
        fun historyBolusTimestamp(pumpMillis: Long, nowMillis: Long): Long = min(pumpMillis, nowMillis)

        /**
         * Which of [current] were not already standing in [previous].
         *
         * A multiset difference, not a set one: the pump can report the same code in two slots at once,
         * and a set difference would drop the second one. Since [PumpAlarm] carries the raw code, two
         * DIFFERENT unrecognised codes are now two different alarms and both are reported. A null
         * [previous] is the first status frame after an app start, where everything the pump reports is
         * news to us.
         */
        @VisibleForTesting
        fun newAlarms(previous: List<PumpAlarm>?, current: List<PumpAlarm>): List<PumpAlarm> {
            val stillStanding = previous?.toMutableList() ?: return current
            return current.filter { !stillStanding.remove(it) }
        }

        // isUrgent, ringsFor and stopsDelivery moved to ApexDriverLogic: the overview needs the same
        // urgent/informational split that the notification tier uses, and a ViewModel must not reach
        // into a @VisibleForTesting member of a Service to get it.

        const val HEARTBEAT_PERIOD_MINUTES = 2
        val FIRST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_9
        val LAST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_12

        /**
         * Reads this driver asks for on its own and whose only product is a log line. A frame of these
         * must never raise a user-facing "invalid object data" card: the user did not ask for the read,
         * cannot act on the card, and the raw bytes already in the log are the answer.
         */
        private val LOG_ONLY_DIAGNOSTIC_OBJECTS = setOf(PumpObject.AlarmEntry, PumpObject.FillEntry)

        /**
         * The little that a bolus match needs to survive the process - see [ApexStringKey.PendingBolusSync].
         *
         * [rawRequestedDose] is kept in the pump's own 0.025 U steps, the same units the history entry
         * carries, so the comparison needs no conversion and cannot drift through a rounding.
         */
        @VisibleForTesting
        data class PendingBolusSync(
            val temporaryId: Long,
            val timestamp: Long,
            val rawRequestedDose: Int,
            val bolusType: BS.Type
        ) {

            fun serialize(): String = "$temporaryId|$timestamp|$rawRequestedDose|${bolusType.name}"

            /**
             * Does [entry] describe this bolus? Deliberately the SAME two rules the in-memory match uses:
             * the pump files every entry at second 59, which is why the minute-away window is accepted, and
             * the dose is compared against what was REQUESTED, because how much actually went in is exactly
             * what was lost with the process.
             */
            fun matches(entryMillis: Long, entryStandardDose: Int, nowMillis: Long): Boolean {
                if (isStale(nowMillis)) return false
                val delta = abs(entryMillis - temporaryId)
                if (delta > 1000 && (delta <= 59000 || delta >= 61000)) return false
                return abs(entryStandardDose - rawRequestedDose) * 0.025 <= 0.11
            }

            fun isStale(nowMillis: Long): Boolean = nowMillis - timestamp > HISTORY_RECENCY_WINDOW

            companion object {

                /** Parses [serialize]; null for empty or anything malformed, so a bad value just disables recovery. */
                fun parse(raw: String): PendingBolusSync? {
                    val parts = raw.split("|")
                    if (parts.size != 4) return null
                    val temporaryId = parts[0].toLongOrNull() ?: return null
                    val timestamp = parts[1].toLongOrNull() ?: return null
                    val dose = parts[2].toIntOrNull() ?: return null
                    val type = BS.Type.entries.firstOrNull { it.name == parts[3] } ?: return null
                    return PendingBolusSync(temporaryId, timestamp, dose, type)
                }
            }
        }
    }

    /**
     * A bolus whose live record was dropped while the pump's own history entry for it was still to
     * come, kept only until that entry can settle the "this bolus may be incomplete" card.
     *
     * It is not a second live bolus and nothing doses from it: `pump.inProgressBolus` is cleared as it
     * always was, so the next bolus is free to start. This holds the one thing that would otherwise be
     * lost with the record - the handle of the card - so the entry that proves how much went in can
     * still take a false warning down, or correct the amount on a true one.
     *
     * The card need not exist yet when the record is kept: on that ending the waiting `deliverTreatment`
     * call raises it, and it has only just been woken. See the comment at the assignment.
     *
     * Read on the frame worker thread inside `bolusEntryLock`. Written from more threads than that:
     * `onBolusFailed` sets it from whichever thread ended the bolus, and [savePendingBolusSync] clears
     * it from every thread that starts, cancels or reconciles one - including the command thread, in
     * `bolus`. Volatile is for visibility; the compare-and-clear is deliberately not made atomic
     * against the read.
     *
     * It does not need to be. The read takes the record only when it still names the bolus the pending
     * slot describes, so the worst a clear landing beside it can do is leave the record unread - and
     * that is the same outcome this design already accepts whenever a new bolus takes the slot over:
     * the card stays as it is, still asking the user to check the pump history.
     */
    @Volatile private var abandonedBolusWithWarning: ApexPump.InProgressBolus? = null

    private fun savePendingBolusSync(pending: PendingBolusSync?) {
        preferences.put(ApexStringKey.PendingBolusSync, pending?.serialize() ?: "")
        // The kept record above exists for one purpose: to settle its own card when its own history
        // entry turns up, and that entry is only ever looked for through the pending record. The moment
        // this slot stops describing the same bolus - a new bolus took it, or it was cleared because
        // nothing is coming - no proof can reach that record any more, so it goes. Its card stays
        // exactly as it is, still saying the dose is in doubt, which is the truth once the proof has
        // become impossible.
        //
        // Centralised here rather than at each call site because there are five of them, and a record
        // left behind by one of them is not inert: it would let one bolus's history entry settle
        // another bolus's card.
        if (abandonedBolusWithWarning?.temporaryId != pending?.temporaryId) abandonedBolusWithWarning = null
    }

    private fun loadPendingBolusSync(): PendingBolusSync? =
        PendingBolusSync.parse(preferences.get(ApexStringKey.PendingBolusSync))

    private data class InCommandResponse(
        var response: CommandResponse? = null,
        /**
         * Written by the waiting thread (under this object's monitor, on timeout) and by the Bluetooth
         * callback thread (under the service monitor) - two different locks, so without `@Volatile`
         * there is no happens-before edge and the callback can keep observing a stale `true`.
         */
        @Volatile var waiting: Boolean = false,
    ) {
        fun clear(notify: Boolean = false) {
            response = null
            waiting = true
            if (notify) this.notifyAll()
        }
    }

    private data class InGetValueResult(
        var isSingleObject: Boolean = false,
        var targetObject: PumpObject? = null,
        /**
         * What the first frame of a history series said the series holds - see
         * [PumpCommand.declaredRecordCount]. Null unless the read is one the maker's own parser reads
         * a count from; byte 2 means something else on the others. Diagnostic only: it is logged
         * against the number of frames that arrived, and decides nothing.
         */
        var declaredRecordCount: Int? = null,
        /** True while reading a value whose byte 2 the maker's parser treats as a record count. */
        var expectsRecordCount: Boolean = false,
        /**
         * Frames of the target object seen for this read, INCLUDING the ones dropped as empty slots
         * or as undecodable. The pump counts what it sent, so a fair comparison has to count the same;
         * [response] holds only what survived.
         */
        var framesSeen: Int = 0,
        /** Volatile for the same reason as [InCommandResponse.waiting]: reader and writers use different locks. */
        @Volatile var waiting: Boolean = false,
        var response: ArrayList<PumpObjectModel>? = null,
        /**
         * The pump answered, but a frame had to be thrown away. Two uses, so do not trim it as inert.
         *
         * It says so in the log: without it a read released by `ApexService.notifyAboutSkippedObject`
         * still reports "Timed out", which sends anyone reading the log after the fact looking at the
         * radio instead of at the frame.
         *
         * And it is how `ApexService.intGetValue` tells "records are arriving that this driver cannot
         * decode" from "the pump said nothing", so a short first-response bound does not abandon a
         * pump that is answering. That matters most for a value whose record layout has never been
         * captured, where a frame the driver cannot decode is the likely answer rather than an
         * exception.
         */
        @Volatile var droppedFrame: Boolean = false,
    ) {
        fun add(data: PumpObjectModel) {
            if (response == null) response = arrayListOf()
            response!!.add(data)
        }

        fun clear(notify: Boolean = false) {
            response = null
            targetObject = null
            isSingleObject = false
            declaredRecordCount = null
            expectsRecordCount = false
            framesSeen = 0
            waiting = true
            droppedFrame = false
            if (notify) this.notifyAll()
        }
    }

    private val commandLock = Any()

    /**
     * Serialises the in-progress-bolus bookkeeping of [onBolusEntry]: every pump object is processed
     * on its own worker thread, so several `BolusEntry` frames of one history read race on
     * `pump.inProgressBolus`.
     *
     * It must NOT be the service monitor (i.e. `@Synchronized` on the method): [notifyAboutResponse]
     * takes the service monitor and runs inline on the Bluetooth callback thread, so a thread that
     * holds the service monitor while waiting for a pump response blocks delivery of the very frames
     * it is waiting for, and every read on the connection stalls until it gives up.
     */
    private val bolusEntryLock = Any()

    /**
     * Guards TBR PumpSync writes and [lastAapsTbr]. Status frames run on their own worker
     * threads, so two V1 frames must not both see "AAPS has no TBR" and insert two rows for the
     * same keypad TBR. The command path (set / cancel) takes the same lock so a stale V1 cannot
     * undo a cancel or restart a TBR SQL just dropped.
     *
     * [processObject] runs on another thread, so waiting for a BLE answer while holding this lock
     * cannot deadlock: the waiter is released in [notifyAboutResponse] before [onTbrChanged] runs.
     *
     * [tbrSyncEpoch] is captured on the BLE callback before [notifyAboutResponse], then passed
     * into [processObject]. Set/cancel increment it at the start (drop checkPump / leftover
     * frames). After the pump Accepted they apply the forced StatusV1 on this thread, then
     * increment again so Heartbeat workers that captured the same epoch cannot Start a stale TBR.
     */
    private val tbrSyncLock = Any()

    @Volatile private var tbrSyncEpoch = 0

    /**
     * Last TBR AAPS had, including after cancel or after SQL dropped the row. Only touch under
     * [tbrSyncLock]. After an early drop, [ApexDriverLogic.tbrDroppedAt] clips the end to the
     * drop time so a same-rate keypad TBR is not hidden until the planned end.
     */
    private var lastAapsTbr: PumpSync.PumpState.TemporaryBasal? = null

    /**
     * Guards [alarmCards]. Every pump object is processed on its own worker thread (see
     * `processObject`), so two status frames can reconcile the cards at the same time. A dedicated
     * lock, not the service monitor, for the reason spelled out at [bolusEntryLock].
     */
    private val alarmCardLock = Any()

    /**
     * One card per standing pump alarm. Lazy because it captures [apexNotifications], which is not
     * injected yet while the field initialisers run.
     */
    private val alarmCards by lazy {
        ApexAlarmCards(
            post = { alarm, sound, into -> postAlarm(alarm, sound, into) },
            dismiss = { handle -> apexNotifications.dismiss(handle) },
            isOnScreen = { handle -> apexNotifications.isOnScreen(handle) },
        )
    }

    private val disposable = CompositeDisposable()

    private val getValueResult = InGetValueResult()
    private val commandResponse = InCommandResponse()

    private var timer = Timer("ApexService-timer")

    // Written under the service monitor as frames arrive, read by the GET thread without it.
    @Volatile private var getValueLastTaskTimestamp: Long = 0
    private var unreachableTimerTask: TimerTask? = null

    private var lastConnectedTimestamp = System.currentTimeMillis()

    /**
     * Last seen keypad-lock state, so [onSystemStateChanged] reports a change instead of every frame.
     * Volatile like its neighbours: each inbound status object is processed on a fresh worker thread.
     */
    @Volatile private var lastIsLocked: Boolean? = null

    /**
     * Last seen answer to "should the low-cartridge card be up", so [onReservoirChanged] acts on the
     * change instead of every frame. Read and written only under [reservoirCardLock].
     */
    private var lastLowReservoir: Boolean? = null

    /**
     * Last seen answer to "should the critical-cartridge card be up", so [onReservoirChanged] acts on
     * the change instead of every frame. Read and written only under [reservoirCardLock].
     */
    private var lastCriticalReservoir: Boolean? = null

    /**
     * Guards [lastLowReservoir], [lastCriticalReservoir] and the cards they drive - status frames
     * overlap on the worker pool.
     */
    private val reservoirCardLock = Any()

    /**
     * Whether the pump's alarm history has already been read once in this service run. Atomic, not
     * just volatile: the read is started from the connect path and a check-then-set on a plain field
     * lets two connects both see false and both run it.
     */
    private val alarmHistoryRead = AtomicBoolean(false)
    private val fillHistoryRead = AtomicBoolean(false)

    /**
     * The serial and password the running gate is testing, snapshotted at the start of the gate from
     * preferences. `onConnect` (card A) and the refuse callbacks read these instead of locals that die
     * when the worker returns; the next gate overwrites them. Not cleared on disconnect - a refuse
     * copies them into the lockout before any teardown, and clearing here would copy null into it.
     */
    @Volatile private var gateSerial: String = ""
    @Volatile private var gatePassword: String = ""

    /**
     * The serial+password the pump refused the data channel for, or null. In memory for the service
     * run only. [isPasswordLockout] holds only while this pair still matches the live serial+password
     * and the toggle is on, so editing any of the three lifts the lockout through the observers below.
     */
    @Volatile private var lockoutSerial: String? = null
    @Volatile private var lockoutPassword: String? = null

    /** So the "not connecting - password lockout" line is logged once per episode, not per KeepAlive tick. */
    @Volatile private var lockoutConnectLogged = false

    /**
     * The password the pump rejected, if it has rejected one.
     *
     * Kept in memory for the service run only, and holds the VALUE rather than a flag, so that
     * changing the password in settings is what makes the driver try again - no observer, no reset
     * path to forget. A restart also clears it, which is the right default for a diagnostic that
     * should not outlive the state it describes.
     */
    @Volatile private var rejectedPassword: String? = null

    /** Result of the last password exchange, awaited by the gate worker and read for card A. */
    private val passwordReplyLock = Any()
    @Volatile private var passwordReply: ApexDriverLogic.PasswordReply? = null

    @Volatile private var manualDisconnect = false
    @Volatile private var doNotReconnect = false
    @Volatile private var connectionFinished = false
    /** Set true when a cancel is requested; consumed by an in-flight bolus() to abort before/after the BLE send. */
    @Volatile private var cancelRequested = false

    @Volatile private var connectionId = 0
    /** Set in onDestroy so the raw worker/reconnect/heartbeat threads terminate instead of running against a dead service. */
    @Volatile private var destroyed = false

    val isReadyForExecutingCommands: Boolean get() = connectionFinished

    val lastConnected: Long
        get() = if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            lastConnectedTimestamp
        } else System.currentTimeMillis()

    private fun intGetValue(value: GetValue.Value, firstResponseTimeoutMs: Long? = null): List<PumpObjectModel>? = synchronized(getValueResult) {
        if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Error - pump is disconnected")
            return null
        }

        getValueResult.clear()
        getValueResult.targetObject = when (value) {
            GetValue.Value.StatusV1 -> PumpObject.StatusV1
            GetValue.Value.StatusV2 -> PumpObject.StatusV2
            GetValue.Value.TDDs -> PumpObject.TDDEntry
            GetValue.Value.Alarms -> PumpObject.AlarmEntry
            GetValue.Value.FillHistory -> PumpObject.FillEntry
            GetValue.Value.BasalProfiles -> PumpObject.BasalProfile
            GetValue.Value.Version -> PumpObject.FirmwareEntry
            GetValue.Value.BolusHistory, GetValue.Value.LatestBoluses -> PumpObject.BolusEntry
            GetValue.Value.WizardStatus -> return null
        }
        // Byte 2 is a record count only on the series the maker's own parser reads one from. On
        // LatestBoluses - a value that parser does not know at all - the captured frame carries 0x80
        // against a ten-record ring, so comparing it to a record count would report a truncation on
        // every bolus read.
        getValueResult.expectsRecordCount = when (value) {
            GetValue.Value.BolusHistory, GetValue.Value.Alarms, GetValue.Value.FillHistory,
            GetValue.Value.TDDs, GetValue.Value.BasalProfiles -> true

            else                                              -> false
        }
        getValueResult.isSingleObject = when (value) {
            GetValue.Value.StatusV1, GetValue.Value.StatusV2, GetValue.Value.Version -> true
            else -> false
        }

        status.addAction(
            ApexDriverStatus.Action.FallbackGettingValue,
            rh.gs(R.string.action_fallback_getting_obj, value.name)
        )

        apexBluetooth.send(GetValue(apexDeviceInfo, value))
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Waiting for response")
            // Two stages when the caller asks for one, because "the pump ignores this value" and "the
            // pump is answering slowly" need different patience. A chunked read is released only by
            // the watcher, 500 ms after the LAST frame, so a single short wait would bound the whole
            // read, not its first record: 270 captured reads of the ten-record bolus ring took a
            // median of 901 ms but as much as 4458 ms. Give up quickly on silence; never cut short a
            // read that is already streaming.
            if (firstResponseTimeoutMs != null) {
                getValueResult.waitMillis(firstResponseTimeoutMs)
                // droppedFrame, not just response: a frame the driver cannot decode leaves response
                // null and only raises that flag, and for a value whose record layout has never been
                // captured - which is the one caller here - that is the LIKELY answer, not an edge
                // case. Without it a pump streaming records this driver cannot parse looks exactly
                // like a pump that said nothing, and the log would report the opposite of the truth.
                if (getValueResult.waiting && getValueResult.response == null && !getValueResult.droppedFrame) {
                    aapsLogger.info(LTag.PUMPCOMM, "Get ${value.name} | Nothing within $firstResponseTimeoutMs ms - giving up early")
                    getValueResult.waiting = false
                    isGetThreadRunning = false
                    status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
                    return null
                }
            }
            if (getValueResult.waiting)
                getValueResult.waitMillis(if (getValueResult.isSingleObject) SINGLE_VALUE_RESPONSE_TIMEOUT else COMPLEX_VALUE_RESPONSE_TIMEOUT)
        } catch (_: InterruptedException) {
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | ${failureReason()}")
            // Disarm the waiter, else a frame arriving after we gave up is appended to the result of
            // this dead read. clear() re-arms waiting on every send, so this cannot leak into the next one.
            getValueResult.waiting = false
            isGetThreadRunning = false
            status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
            return null
        }

        // A read that timed out part way through still holds the frames it did get, so the success
        // path below would return them and leave the chunked-read thread alive with the waiter still
        // armed. That thread then fires into whatever read starts next and releases it with nothing in
        // hand - a status read that reports a timeout it never had, and, for any caller that did not
        // ask for noOptimizations, a teardown and a reconnect. A waiting flag that is still true here
        // means exactly "the watcher never released us", so this is where to close it.
        if (getValueResult.waiting) {
            // Only say "part way through" when something actually arrived. Every ordinary timeout
            // lands here too, and the branch below already logs the true reason for those.
            if (getValueResult.response != null || getValueResult.droppedFrame)
                aapsLogger.warn(LTag.PUMPCOMM, "Get ${value.name} | Timed out part way through - closing the read")
            getValueResult.waiting = false
            isGetThreadRunning = false
        }

        if (getValueResult.response == null) {
            val reason = if (getValueResult.droppedFrame) "Answered, but the frame could not be used" else failureReason()
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | $reason")
            getValueResult.waiting = false
            isGetThreadRunning = false
            status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
            return null
        }

        aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Completed")
        status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
        getValueResult.response
    }

    private fun intExecuteWithResponse(command: DeviceCommand): CommandResponse? = synchronized(commandResponse) {
        if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            aapsLogger.debug(LTag.PUMPCOMM, "$command | Error - pump is disconnected")
            return null
        }

        status.addAction(
            ApexDriverStatus.Action.FallbackExecutingCommand,
            rh.gs(R.string.action_fallback_executing_obj, command.toString())
        )

        commandResponse.clear()
        apexBluetooth.send(command)
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "$command | Waiting for response")
            commandResponse.waitMillis(COMMAND_RESPONSE_TIMEOUT)
        } catch (_: InterruptedException) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | ${failureReason()}")
            commandResponse.waiting = false
            status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
            return null
        }

        if (commandResponse.response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | ${failureReason()}")
            commandResponse.waiting = false
            status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
            return null
        }

        aapsLogger.debug(LTag.PUMPCOMM, "$command | Completed")
        status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
        commandResponse.response
    }

    /**
     * Why a read or a command produced no answer, as far as the driver can tell.
     *
     * Both outcomes used to be logged as "Timed out", which hides the difference that matters when
     * reading a log afterwards: a pump that is connected but did not answer within the timeout is a
     * protocol or firmware question, while a link that dropped mid-exchange is a radio question. The
     * two call for completely different investigations and, in a real log, the second was by far the
     * more common one while looking exactly like the first.
     */
    private fun failureReason(): String =
        if (apexBluetooth.status != ApexBluetooth.Status.CONNECTED) "Aborted - link dropped while waiting (${apexBluetooth.status})"
        else "Timed out"

    private val cannotBeReconnected: Boolean
        get() =
            apexBluetooth.status != ApexBluetooth.Status.CONNECTED || // if pump was disconnected
            doNotReconnect || // if some command has already requested reconnect
            !connectionFinished || // if driver is initializing
            pump.inProgressBolus?.lockHistory == true // if driver is listening to bolus progress

    fun getValue(value: GetValue.Value, noOptimizations: Boolean = false, firstResponseTimeoutMs: Long? = null): List<PumpObjectModel>? {
        synchronized(commandLock) {
            val firstTry = intGetValue(value, firstResponseTimeoutMs)
            if (firstTry != null || noOptimizations || cannotBeReconnected) return@getValue firstTry
            doNotReconnect = true
            status.addAction(ApexDriverStatus.Action.Reconnecting, R.string.action_reconnecting)
            disconnect(true)
        }

        if (!ensureConnected()) {
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | Timed out waiting for reconnection")
            status.removeAction(ApexDriverStatus.Action.Reconnecting)
            synchronized(commandLock) { doNotReconnect = false }
            return null
        }

        status.removeAction(ApexDriverStatus.Action.Reconnecting)
        synchronized(commandLock) {
            doNotReconnect = false
            val final = intGetValue(value, firstResponseTimeoutMs)
            return@getValue final
        }
    }

    private fun executeWithResponse(command: DeviceCommand, noOptimizations: Boolean = false): CommandResponse? {
        synchronized(commandLock) {
            val firstTry = intExecuteWithResponse(command)
            if (firstTry != null || noOptimizations || cannotBeReconnected) return@executeWithResponse firstTry
            doNotReconnect = true
            status.addAction(ApexDriverStatus.Action.Reconnecting, R.string.action_reconnecting)
            disconnect(true)
        }

        if (!ensureConnected()) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | Timed out waiting for reconnection")
            status.removeAction(ApexDriverStatus.Action.Reconnecting)
            synchronized(commandLock) { doNotReconnect = false }
            return null
        }

        status.removeAction(ApexDriverStatus.Action.Reconnecting)
        synchronized(commandLock) {
            doNotReconnect = false
            val final = intExecuteWithResponse(command)
            return@executeWithResponse final
        }
    }

    private fun ensureConnected(): Boolean {
        var times = 0
        while (!connectionFinished && times < 100) {
            aapsLogger.debug(LTag.PUMPCOMM, "Waiting for successful connection")
            SystemClock.sleep(500)
            times++
        }
        return connectionFinished
    }

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMP, "Service created")
        wakeLock = try {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.also { it.setReferenceCounted(false) }
        } catch (t: Throwable) {
            aapsLogger.error(LTag.PUMP, "Could not create the wake lock - the driver runs without one", t)
            null
        }
        apexBluetooth.setCallback(this)

        // A previous instance of this service may have left alarm cards behind: the handles die with
        // the service, while the notification store is a singleton and keeps the cards. Nothing else in
        // the app posts these ids, so clearing them by id is safe here, and the first status
        // frame puts back - without sound - whatever still stands.
        apexNotifications.clearAlarmCards()
        apexNotifications.dismiss(ApexNotifications.RESERVOIR_LOW)

        // RECEIVER_NOT_EXPORTED: ACTION_STATE_CHANGED is a protected system broadcast, and API 34+
        // requires the flag for every dynamically registered receiver. ContextCompat does the gating.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Per-key flows also deliver changes made by import or by the driver while settings are closed.
        disposable += apexPreferenceChanges(preferences)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           when (it.changedKey) {
                               ApexStringKey.FirmwareVer.key    -> onFwVerChanged()
                               ApexStringKey.SerialNumber.key   -> onSerialChanged()
                               // Editing the password or its toggle lifts a lockout keyed on the old value and
                               // retries at once, so a pump the user just fixed reconnects without waiting for
                               // a KeepAlive.
                               ApexStringKey.PumpPassword.key,
                               ApexBooleanKey.PumpHasPassword.key -> onPasswordSettingsChanged()

                               ApexStringKey.CalcBatteryType.key, ApexDoubleKey.BatteryLowVoltage.key,
                               ApexDoubleKey.BatteryHighVoltage.key,
                               ApexBooleanKey.CalculateBatteryPercentage.key -> onBatteryStuffChanged()

                               // Switching the diagnostic on re-arms it. Without this the once-per-service-run
                               // flag would already be spent by the time the user turns it on to capture
                               // something specific - a fill after a cartridge change, say - and they would get
                               // an empty log with nothing to explain it.
                               ApexBooleanKey.ReadFillHistory.key ->
                                   if (preferences.get(ApexBooleanKey.ReadFillHistory)) fillHistoryRead.set(false)

                               ApexDoubleKey.MaxBolus.key       ->
                                   if (pump.maxBolus != preferences.get(ApexDoubleKey.MaxBolus)) updateSettings("ApexService-PreferencesListener-MaxBolus")

                               ApexDoubleKey.MaxBasal.key       ->
                                   if (pump.maxBasal != preferences.get(ApexDoubleKey.MaxBasal)) updateSettings("ApexService-PreferencesListener-MaxBasal")

                               ApexStringKey.AlarmSoundLength.key ->
                                   if (pump.lastV2?.alarmLength?.name != preferences.get(ApexStringKey.AlarmSoundLength)) updateSettings("ApexService-PreferencesListener-AlarmSoundLength")

                               ApexStringKey.BolusSpeed.key     -> updateSettings("ApexService-PreferencesListener-BolusSpeed")
                           }
                       }, fabricPrivacy::logException)

        pump.serialNumber = apexDeviceInfo.serialNumber
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.PUMP, "Service destroyed")
        destroyed = true
        timer.cancel()
        unregisterReceiver(bluetoothStateReceiver)
        // Release the singleton ApexBluetooth's reference to this destroyed service, else the
        // @SingleIn(AppScope::class) retains it forever (leak).
        apexBluetooth.clearCallback(this)
        // Only this driver posts this card and only a status frame can take it down, so a driver that
        // never runs again - the plugin switched off, another pump selected - would leave it on the
        // overview list with no owner. The next instance re-posts it on its first frame if the
        // cartridge is still low. Under the same lock the poster uses, so a frame already inside
        // onReservoirChanged finishes first and cannot put the card back after this.
        synchronized(reservoirCardLock) {
            apexNotifications.dismiss(ApexNotifications.RESERVOIR_LOW)
            apexNotifications.dismiss(ApexNotifications.RESERVOIR_CRITICAL)
        }
        // shutdown(), not shutdownNow(): a frame being processed right now may be finishing a bolus
        // hand-off, and interrupting that is worse than letting it run to the end on a dying service.
        frameExecutor.shutdown()
        // Give the lock back whatever the count says. Quiet when nothing was held, which is the
        // ordinary case - a service is usually destroyed while idle, and a warning there would fire
        // on every teardown and teach the reader to ignore the one that matters.
        synchronized(wakeLockGuard) {
            val wasHeld = wakeLockHolders > 0
            wakeLockHolders = 0
            if (wasHeld) {
                aapsLogger.warn(LTag.PUMP, "Service destroyed while the wake lock was still held - releasing it")
                try {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                } catch (t: Throwable) {
                    aapsLogger.error(LTag.PUMP, "Could not give back the wake lock on destroy", t)
                }
            }
        }
        disposable.clear()
        serviceScope.cancel()
        disconnect()
        super.onDestroy()
    }

    private fun onSerialChanged() {
        aapsLogger.debug(LTag.PUMP, "Serial number changed to: ${apexDeviceInfo.serialNumber}")
        // A different pump: the old lockout and its card describe a pump the user is no longer pointing
        // at. Drop them so a card B for pump A does not sit over a fresh connection to pump B.
        clearPasswordLockout()
        pump.serialNumber = apexDeviceInfo.serialNumber
        pump.isInitialized = false
        pump.cachedBasalProfile = null
        pump.lastStatusReceivedAt = 0
        if (apexBluetooth.status != ApexBluetooth.Status.DISCONNECTED) {
            aapsLogger.debug(LTag.PUMP, "Disconnecting due to serial number change")
            disconnect()
        }
        // The cached address short-circuits scanning, so without dropping it a new serial number
        // would keep reconnecting to the previously paired pump (e.g. after importing settings from
        // another phone) and never scan for the pump the user actually asked for.
        preferences.put(ApexStringKey.BluetoothAddress, "")
        aapsLogger.debug(LTag.PUMP, "Starting reconnection with new serial number")
        startConnection()
    }

    private fun onFwVerChanged() {
        if (apexBluetooth.status != ApexBluetooth.Status.DISCONNECTED) disconnect()
        startConnection()
    }

    private fun onBatteryStuffChanged() {
        getStatus("ApexService-onBatteryStuffChanged", force = true)
    }

    //////// Public methods

    fun checkPump(caller: String, optimize: Boolean = false): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "checkPump - $caller")

        try {
            status.addAction(ApexDriverStatus.Action.CheckingPump, R.string.action_checking_pump)
            // No StatusV2 here. All three callers are about to dose and hold the command lock while
            // they do, so waiting up to 10 s for a battery voltage would only delay the bolus or the
            // temp basal. Every value this check exists for comes from StatusV1.
            return getStatus("ApexService-checkPump", optimize, readV2 = false)
        } finally {
            status.removeAction(ApexDriverStatus.Action.CheckingPump)
        }
    }

    fun syncDateTime(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "syncDateTime - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingDateTime, R.string.action_updating_dt)
        val response = executeWithResponse(SyncDateTime(apexDeviceInfo, DateTime.now()), noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.UpdatingDateTime)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[syncDateTime caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to sync time: ${response.code.name}")
            return false
        }

        return true
    }

    fun requestHeartbeat(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "requestHeartbeat - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "requestHeartbeat isn't supported yet.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.RequestingHeartbeat, R.string.action_requesting_heartbeat)
        val response = executeWithResponse(RequestHeartbeat(apexDeviceInfo, HEARTBEAT_PERIOD_MINUTES), noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.RequestingHeartbeat)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[requestHeartbeat caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to notify about connection: ${response.code.name}")
            return false
        }

        return true
    }

    fun bolus(dbi: DetailedBolusInfo, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus - $caller")
        cancelRequested = false
        if (pump.inProgressBolus != null) {
            aapsLogger.error(LTag.PUMPCOMM, "[bolus caller=$caller] Bolus already in progress, refusing new bolus")
            return false
        }
        if (dbi.insulin > pump.maxBolus) {
            aapsLogger.error(LTag.PUMP, "[bolus caller=$caller] Requested ${dbi.insulin}U is greater than maximum set ${pump.maxBolus}")
            return false
        }

        val doseRaw = (dbi.insulin / 0.025).roundToInt()
        // The pump stamps every history entry at second 59 of the minute, so the id is built on that
        // grid to make the onBolusEntry match window work. The millisecond-of-second MUST be carried
        // over, otherwise two boluses started inside the same minute (SMB followed by a manual bolus)
        // produce an identical temporaryId: the second addBolusWithTempId is then skipped as a
        // duplicate and syncBolusWithTempId rewrites the first bolus' row, so one bolus vanishes from
        // the DB and IOB is understated.
        val nowMillis = System.currentTimeMillis()
        val temporaryId = DateTime(nowMillis).withSecondOfMinute(59).withMillisOfSecond((nowMillis % 1000).toInt()).millis

        val action = if (dbi.bolusType == BS.Type.SMB)
            ApexDriverStatus.Action.SettingMicroBolus
        else
            ApexDriverStatus.Action.SettingBolus

        if (!checkPump("ApexService-bolus", optimize = true)) return false

        if (cancelRequested) {
            cancelRequested = false
            aapsLogger.debug(LTag.PUMPCOMM, "[bolus caller=$caller] Cancelled before BLE send (cancel pressed during checkPump)")
            return false
        }

        status.addAction(action, rh.gs(
            if (dbi.bolusType == BS.Type.SMB)
                R.string.action_setting_smb
            else
                R.string.action_setting_bolus,
            dbi.insulin,
        ))
        val response = executeWithResponse(Bolus(apexDeviceInfo, doseRaw), noOptimizations = true)
        status.removeAction(action)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[bolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code == CommandResponse.Code.Invalid) {
            if (pump.inProgressBolus != null) {
                aapsLogger.warn(LTag.PUMPCOMM, "[caller=$caller] Pump rejected bolus: bolus already delivering")
            } else {
                aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Cannot begin bolus: pump in special mode")
                createSpecialModeAlarm()
            }
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to begin bolus: ${response.code.name}")
            return false
        }

        if (cancelRequested) {
            cancelRequested = false
            aapsLogger.debug(LTag.PUMPCOMM, "[bolus caller=$caller] Cancel requested during Bolus BLE — sending CancelBolus immediately")
            status.addAction(ApexDriverStatus.Action.CancelingBolus, R.string.action_canceling_bolus)
            executeWithResponse(CancelBolus(apexDeviceInfo))
            status.removeAction(ApexDriverStatus.Action.CancelingBolus)
            return false
        }

        val syncResult = pumpSync.addBolusWithTempId(
            timestamp = dbi.timestamp,
            amount = dbi.insulin,
            temporaryId = temporaryId,
            type = dbi.bolusType,
            pumpSerial = apexDeviceInfo.serialNumber,
            pumpType = PumpType.APEX_TRUCARE_III,
        )
        if (syncResult)
            aapsLogger.debug(LTag.PUMP, "Initial bolus [${dbi.insulin}U] sync succeeded (tempId $temporaryId)")
        else
        // Not cosmetic: a rejected insert means the DB already holds a record under this temporaryId,
        // so the bolus about to be delivered will not get its own row and its insulin will be missing
        // from IOB. Loud enough to be findable in an exported log.
            aapsLogger.error(LTag.PUMP, "Initial bolus [${dbi.insulin}U] sync REJECTED - duplicate tempId $temporaryId, this bolus may not be recorded!")

        // Survives the process. The match below runs off pump.inProgressBolus, which is memory only, and a
        // link drop mid-bolus is exactly when the process tends to go away with it - see [PendingBolusSync].
        savePendingBolusSync(PendingBolusSync(temporaryId, dbi.timestamp, doseRaw, dbi.bolusType))

        pump.inProgressBolus = ApexPump.InProgressBolus(
            requestedDose = dbi.insulin,
            temporaryId = temporaryId,
            detailedBolusInfo = dbi,
            lockHistory = true,
            treatment = ApexPump.Treatment(
                insulin = dbi.insulin,
                carbs = dbi.carbs.toInt(),
                isSMB = dbi.bolusType == BS.Type.SMB,
                id = dbi.id
            )
        )

        return true
    }

    fun temporaryBasal(dose: Double, durationMinutes: Int, type: PumpSync.TemporaryBasalType? = null, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "temporaryBasal - $caller")
        // A pump standing on a motor-stop alarm still ACCEPTS this command - only boluses come back
        // rejected - and the driver would then write the rate into AAPS as a temporary basal that is
        // running. Measured on 2026-08-31: five of them, up to 1.875 U/h, while the pump's own
        // delivered-dose counter stood still for 26 minutes.
        //
        // `ApexPump.isSuspended` now reports the same state, so the loop stops asking before it gets
        // here (`LoopPlugin.applyTBRRequest`). This guard is for everything that does not go through
        // that check - a temp set by hand, by an automation, or by a queued command that was already
        // in flight when the pump stopped.
        //
        // Only this function. Cancelling goes through `cancelTemporaryBasal`, which is not gated
        // here, and `ApexPumpPlugin.cancelTempBasal` deliberately checks only [ApexPump.isBasalPaused]
        // so that it stays open in exactly this state: a temp that was already running when the motor
        // stopped must still be cancellable, and asking for less insulin is never the unsafe
        // direction.
        //
        // The latch, NOT `pump.alarms`. The pump empties its alarm slots for a single frame at a time
        // while the motor is still stopped - 20:58:57 on 2026-08-31, with the same alarm back at
        // 21:00:37 - so reading the slots directly would open this guard for one frame inside the
        // very window the latch exists to cover, and for exactly the callers named above.
        if (pump.isDeliveryStoppedByAlarm) {
            aapsLogger.error(
                LTag.PUMP,
                "[temporaryBasal caller=$caller] Refusing ${dose}U/h: the pump is standing on an alarm that stops delivery"
            )
            return false
        }
        if (dose > pump.maxBasal) {
            aapsLogger.error(LTag.PUMP, "[temporaryBasal caller=$caller] Requested ${dose}U is greater than maximum set ${pump.maxBasal}U")
            return false
        }

        val doseRaw = (dose / 0.025).roundToInt()

        val durationRaw = durationMinutes / 15
        if (durationRaw <= 0) {
            aapsLogger.error(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Duration ${durationMinutes}min is below the 15 minute step - refusing")
            return false
        }
        if (durationMinutes % 15 > 0) aapsLogger.warn(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] TBR duration is not aligned to 15 minute steps! Rounded down.")

        if (!checkPump("ApexService-temporaryBasal", optimize = true)) return false

        status.addAction(
            ApexDriverStatus.Action.SettingTBR,
            rh.gs(R.string.action_setting_tbr, dose, durationMinutes)
        )
        // Hold [tbrSyncLock] across the pump command, the PumpSync write, and the forced status
        // read. processObject is another thread, so this wait cannot deadlock; it only delays
        // keypad TBR sync until AAPS has the row we just wrote. StatusV2 is not needed here.
        synchronized(tbrSyncLock) {
            tbrSyncEpoch++
            val response = executeWithResponse(TemporaryBasal(apexDeviceInfo, true, durationRaw, doseRaw))
            status.removeAction(ApexDriverStatus.Action.SettingTBR)

            if (response == null) {
                aapsLogger.error(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
                return false
            }

            if (response.code != CommandResponse.Code.Accepted) {
                aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to start temporary basal: ${response.code.name}")
                return false
            }

            val id = System.currentTimeMillis()
            val durationMs = durationRaw.toLong() * 15 * 60 * 1000
            val rate = doseRaw * 0.025
            // The profile is read inside the same block as the write, for the same timestamp core
            // uses, so the label is measured rather than inferred from a second lookup a moment later.
            val profileRunning = runBlocking { profileFunction.getProfile(id) != null }
            val written = pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = id,
                pumpId = id,
                pumpType = PumpType.APEX_TRUCARE_III,
                pumpSerial = apexDeviceInfo.serialNumber,
                // Record the rate the pump actually runs (encoded in 0.025 U steps), not the raw
                // request — otherwise AAPS IOB diverges for rates not aligned to 0.025 U.
                rate = rate,
                // Record the duration the pump actually runs (15-min steps, rounded down), not the
                // requested minutes — otherwise AAPS IOB diverges for non-15-aligned TBR durations.
                duration = durationMs,
                isAbsolute = true,
                type = type,
            )
            if (written) onTbrSyncAccepted()
            else logTbrSyncRefused(
                source = "temporaryBasal caller=$caller",
                refusal = ApexDriverLogic.tbrStartRefusal(profileRunning),
                facts = "rate=${rate}U/h duration=${durationMs / 60000}min pumpId=$id",
            )
            rememberLastAapsAfterWrite(
                written = written,
                timestamp = id,
                durationMs = durationMs,
                // The synthetic fallback row mimics the database row, which stores absolute
                // rates multiplied by the insulin concentration.
                rate = rate * insulinConcentration(),
                isAbsolute = true,
                type = type ?: PumpSync.TemporaryBasalType.NORMAL,
            )

            aapsLogger.debug(LTag.PUMP, "Started TBR ${dose}U for ${durationMinutes}min by $caller")
            readStatusV1ForTbrCommand()
        }
        if (type == PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND) {
            aapsLogger.debug(LTag.PUMP, "Emulated pump suspend detected - disconnecting pump after the queue ends.")
            onEmulatedSuspend()
        }
        return true
    }

    fun cancelBolus(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelBolus - $caller")
        // Signal any in-flight bolus() (between BLE send and inProgressBolus assignment) to abort.
        cancelRequested = true

        pump.inProgressBolus?.let {
            // Communication would take longer than just finishing the bolus.
            if (it.requestedDose - it.currentDose < 0.10) {
                aapsLogger.debug(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Skipping, progress ${it.currentDose} / ${it.requestedDose} U")
                return@cancelBolus true
            }
        } ?: return true

        // A bolus is actually delivering — cancel it directly rather than via the flag.
        cancelRequested = false
        status.addAction(ApexDriverStatus.Action.CancelingBolus, R.string.action_canceling_bolus)
        val response = executeWithResponse(CancelBolus(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.CancelingBolus)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel bolus: ${response.code.name}")
            return false
        }

        onBolusFailed(true)
        // force = true for the same reason as the post-bolus refresh: the pump has just stopped
        // mid-delivery, so the reservoir and the daily total we hold are stale whatever their age.
        getStatus("ApexService-cancelBolus", force = true)
        return true
    }

    fun cancelTemporaryBasal(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelTemporaryBasal - $caller")

        status.addAction(ApexDriverStatus.Action.CancelingTBR, R.string.action_canceling_tbr)
        synchronized(tbrSyncLock) {
            tbrSyncEpoch++
            val snapshot = pumpSync.expectedPumpState().temporaryBasal

            val response = executeWithResponse(CancelTemporaryBasal(apexDeviceInfo))
            status.removeAction(ApexDriverStatus.Action.CancelingTBR)

            if (response == null) {
                aapsLogger.error(LTag.PUMPCOMM, "[cancelTemporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
                return false
            }

            if (response.code != CommandResponse.Code.Accepted) {
                aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel temporary basal: ${response.code.name}")
                return false
            }

            val stop = System.currentTimeMillis()
            // Clip to the actual cancel time. If AAPS had no row (keypad TBR), remember the last
            // pump TBR so a leftover V1 cannot Start it again.
            val dropped = snapshot?.let { ApexDriverLogic.tbrDroppedAt(it, stop) }
                ?: ApexDriverLogic.lastAapsFromPumpTbr(pump.tbr, stop, insulinConcentration())
            dropped?.let { lastAapsTbr = it }
            val closed = pumpSync.syncStopTemporaryBasalWithPumpId(
                timestamp = stop,
                endPumpId = stop,
                pumpType = PumpType.APEX_TRUCARE_III,
                pumpSerial = apexDeviceInfo.serialNumber,
            )
            // hadRunningRow comes from the snapshot read before the command went out, so it can be
            // stale by the time core looks - which is why the label is NothingToClose, not a claim
            // about what is open now.
            if (closed) onTbrSyncAccepted()
            else logTbrSyncRefused(
                source = "cancelTemporaryBasal caller=$caller",
                refusal = ApexDriverLogic.tbrStopRefusal(hadRunningRow = snapshot != null),
                facts = "stop=$stop snapshotBeforeCommand=${snapshot != null}",
            )

            readStatusV1ForTbrCommand()
            return true
        }
    }

    /**
     * A max-bolus or max-basal limit for the settings write, never below what the pump already holds.
     *
     * These two preferences are a mirror of the pump, not a setting of their own: `onStatusV1` copies
     * the pump's values back into them on every frame that differs. But nothing stopped a hand-typed
     * lower number from being written straight back to the pump, where it becomes a hard delivery cap -
     * and the AAPS loop ceiling follows it down through `pumpDescription.maxTempAbsolute`. The maker's
     * own editor refuses the same thing: its picker starts at the highest value already programmed.
     *
     * Lowering a pump limit stays possible - on the pump's own keypad, which is the deliberate place
     * for it. AAPS has its own separate limits for what the loop may ask for.
     *
     * [prefU] is in insulin units, as the preference stores it. [pumpRaw] and the returned value are
     * in the units the wire uses: 0.025 U steps.
     */
    private fun limitNotBelowPump(prefU: Double, pumpRaw: Int, name: String, caller: String): Int {
        val wanted = (prefU / 0.025).roundToInt()
        if (wanted >= pumpRaw) return wanted
        aapsLogger.warn(
            LTag.PUMPCOMM,
            "[updateSettings caller=$caller] Not lowering the pump's $name from ${pumpRaw * 0.025}U to " +
                "${wanted * 0.025}U - keeping the pump's own value. Lower it on the pump if that is really wanted."
        )
        return pumpRaw
    }

    fun updateSettings(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSettings - $caller")

        // UpdateSettingsV1 is built by patching the pump's last StatusV1. Without one (pump not connected
        // yet) we can't construct the command — skip; the change is re-applied on connect via
        // onSettingsChanged (settingsAreUnadvised). Avoids an NPE when settings change while disconnected.
        val lastV1 = pump.lastV1
        if (lastV1 == null) {
            aapsLogger.warn(LTag.PUMPCOMM, "[updateSettings caller=$caller] No pump status yet — deferring until connected")
            return false
        }

        status.addAction(ApexDriverStatus.Action.UpdatingSettings, R.string.action_updating_settings)
        val bolusSpeed = BolusDeliverySpeed.entries.firstOrNull { it.name == preferences.get(ApexStringKey.BolusSpeed) }
            ?: BolusDeliverySpeed.Standard
        // Falls back to the key's own default, so an unreadable setting can never put a byte on the
        // wire that a fresh install would not send anyway.
        val storedAlarmLength = preferences.get(ApexStringKey.AlarmSoundLength)
        val alarmLength = storedAlarmLength.toEnumOrNull<AlarmLength>() ?: AlarmLength.Short.also {
            aapsLogger.warn(LTag.PUMPCOMM, "[updateSettings caller=$caller] Unknown alarm length setting '$storedAlarmLength' - using ${it.name}")
        }
        val response = executeWithResponse(
            lastV1.toUpdateSettingsV1(
                apexDeviceInfo,
                alarmLength,
                bolusSpeed = bolusSpeed,
                maxSingleBolus = limitNotBelowPump(
                    preferences.get(ApexDoubleKey.MaxBolus), lastV1.maxBolus, "MaxBolus", caller
                ),
                maxBasalRate = limitNotBelowPump(
                    preferences.get(ApexDoubleKey.MaxBasal), lastV1.maxBasal, "MaxBasal", caller
                ),
                enableAdvancedBolus = false,
            )
        )
        status.removeAction(ApexDriverStatus.Action.UpdatingSettings)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSettings caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            // Firmware 1.1 returns Invalid for UpdateSettings even though the settings are applied;
            // treat it as non-fatal there (matches the reference driver).
            val fw = pump.firmwareVersion
            if (fw == null || fw.firmwareMajor != 1 || fw.firmwareMinor != 1) {
                aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update settings: ${response.code.name}")
                return false
            }
            aapsLogger.warn(LTag.PUMPCOMM, "[caller=$caller] UpdateSettings returned Invalid for fw 1.1 (skipping, not critical)")
            return true
        }

        return true
    }

    fun updateSystemState(suspend: Boolean, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSystemState - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingSystemState, R.string.action_updating_sys_state)
        val response = executeWithResponse(UpdateSystemState(apexDeviceInfo, suspend))
        status.removeAction(ApexDriverStatus.Action.UpdatingSystemState)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSystemState caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update system state: ${response.code.name}")
            return false
        }

        return true
    }

    fun updateBasalPatternIndex(id: Int, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateBasalPatternIndex - $caller")

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileIndex, R.string.action_setting_basal_no)
        // 0x04 + one index byte first, on every protocol version.
        //
        // This used to send 0x34 + 16 bytes on protocol 4.11 and newer and keep 0x04 as the fallback.
        // A capture on 2026-09-02 from a pump on protocol 4.12 shows that order is backwards: the pump
        // answered Invalid to 0x34 and Accepted to 0x04, twice in one profile switch. The maker's own
        // app agrees - it selects the pattern with 0x04 and one byte unconditionally, with no protocol
        // branch anywhere, and uses 0x34 for a different object entirely (a 16-byte audio-bolus block).
        // So 0x34 was a guess taken from another driver, and it cost a doomed write plus a retry, about
        // 3.3 s, on every profile switch.
        //
        // The 0x34 attempt is kept as the fallback rather than deleted: no capture from an older
        // firmware exists, and a pump that really wants 0x34 must still work. It is now the branch that
        // has to prove itself, not the default.
        var response = executeWithResponse(UpdateUsedBasalProfile(apexDeviceInfo, id, proto411Plus = false))
        if (response != null && response.code == CommandResponse.Code.Invalid) {
            aapsLogger.warn(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] 0x04 format returned Invalid, retrying with the 0x34 format")
            response = executeWithResponse(UpdateUsedBasalProfile(apexDeviceInfo, id, proto411Plus = true))
        }
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileIndex)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern index: ${response.code.name}")
            return false
        }

        // No EventProfileSwitchChanged here, deliberately. On this version that event is not a UI
        // hint: the command queue answers it by running setProfile and writing an
        // EffectiveProfileSwitch, and a pattern-index write - which can come from a status frame -
        // must not start that by itself. KeepAliveWorker raises the same event on its own five-minute
        // tick whenever isThisProfileSet is false, so a profile that really is out of step is still
        // reconciled, just not within the same second.
        return true
    }

    /**
     * @param targetsUsedPattern the caller has just selected pattern [USED_BASAL_PATTERN_INDEX],
     *   but no status frame has confirmed it yet - trust the write to land there anyway.
     */
    fun updateCurrentBasalPattern(doses: List<Double>, caller: String, targetsUsedPattern: Boolean = false): Boolean {
        require(doses.size == 48)

        aapsLogger.debug(LTag.PUMPCOMM, "updateCurrentBasalPattern - $caller")

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileContents, R.string.action_setting_basal_profile)
        val rawDoses = doses.map { (it / 0.025).roundToInt() }
        val response = executeWithResponse(UpdateBasalProfileRates(apexDeviceInfo, rawDoses))
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileContents)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern rates: ${response.code.name}")
            return false
        }

        // UpdateBasalProfileRates writes the pattern the pump is CURRENTLY using, while the cache
        // is defined as pattern USED_BASAL_PATTERN_INDEX. Stamping it after a write into another
        // pattern made isThisProfileSet report a profile the pump does not run. Clearing the cache
        // instead makes the next KeepAlive check re-verify and rewrite.
        if (targetsUsedPattern || pump.currentBasalPattern == USED_BASAL_PATTERN_INDEX) {
            pump.cachedBasalProfile = doses
        } else {
            aapsLogger.warn(
                LTag.PUMPCOMM,
                "[caller=$caller] Rates written while the pump is on pattern ${pump.currentBasalPattern}, not $USED_BASAL_PATTERN_INDEX - not caching them as the used profile"
            )
            pump.cachedBasalProfile = null
        }
        return true
    }

    fun getTDDs(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getTDDs - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "TDDs are unreliable on 6.25 and older!")
            return false
        }

        status.addAction(ApexDriverStatus.Action.GettingTDDs, R.string.action_getting_tdds)
        val response = getValue(GetValue.Value.TDDs)
        status.removeAction(ApexDriverStatus.Action.GettingTDDs)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getTDDs caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    /**
     * Read the pump's own alarm history once per service run, into the log and nowhere else.
     *
     * The driver had declared [GetValue.Value.Alarms] since it was written without ever asking for
     * it, so until 2026-08-31 the record layout had never been seen. It is the second, independent
     * reading of an event the live status frame also reports, and one open question needed exactly
     * that: a captured
     * session has the pump putting a code in the status frame that does not decode to LowReservoir at
     * the moment the cartridge crossed the pump's own low level.
     *
     * That question now has an answer, and it is a stronger one than expected. The read of 2026-08-31
     * returned the pump's whole stored history - 66 records, 2026-05-05 to 2026-08-31 - and not one
     * of them is a low-reservoir alarm. What is there: 52 NoDelivery, 7 ButtonError, 4 DeadBattery
     * and 3 LowBattery. Through that whole window the pump was set to warn at 20 U and at two hours
     * of insulin left, and many cartridges were used up.
     *
     * So the pump does not put its low-reservoir warning where AAPS can see it, in the history or in
     * the live status slots, and the driver's own reservoir card is not a second opinion but the only
     * one there is. This does not prove the pump can never store such a record - the history is a
     * ring and the 52 NoDelivery records came in bursts that may have pushed older ones out - but it
     * does mean nothing may be built on the pump raising it.
     *
     * A read, not a command: nothing here changes pump state. `noOptimizations` so a pump that does
     * not answer this value costs a quiet failure instead of a teardown and a reconnect - failing
     * quietly is the whole contract of a diagnostic. The flag is set before the read, so one attempt
     * per service run is all this can ever cost, whether it works or not.
     *
     * It runs at the end of the connect, where the driver has already told the command queue it is
     * ready, so it holds the command lock in a window where an insulin command can arrive. That is
     * why it gives the pump only [ALARM_HISTORY_RESPONSE_TIMEOUT] to produce its FIRST record,
     * instead of the 30 s a history read normally gets: a pump that answers answers at once, and a
     * pump that does not must not stand in front of a bolus for half a minute. A read that has
     * started streaming still gets the full time - the short bound is about silence, not speed.
     */
    private fun readAlarmHistoryOnce() {
        if (!alarmHistoryRead.compareAndSet(false, true)) return
        aapsLogger.info(LTag.PUMPCOMM, "ALARM-HISTORY: reading the pump's alarm history once, for the log only")
        status.addAction(ApexDriverStatus.Action.GettingAlarms, R.string.action_getting_alarms)
        val response = try {
            getValue(GetValue.Value.Alarms, noOptimizations = true, firstResponseTimeoutMs = ALARM_HISTORY_RESPONSE_TIMEOUT)
        } finally {
            status.removeAction(ApexDriverStatus.Action.GettingAlarms)
        }
        if (response == null) aapsLogger.info(LTag.PUMPCOMM, "ALARM-HISTORY: the pump did not answer the alarm history read")
        else aapsLogger.info(LTag.PUMPCOMM, "ALARM-HISTORY: read finished, ${response.size} records")
    }

    /**
     * Run the connection-password exchange for one connection, on the gate worker, before the data
     * channel is subscribed. Called from `ApexBluetooth.onMtuChanged` only when the toggle is on and
     * the pump has the password service, so the toggle-off connect path never reaches here.
     *
     * The pump refuses the data-channel subscription until it has seen a valid FFC1 write, so the
     * order is the maker's: subscribe FFC2, write FFC1, wait for the write to complete, then probe the
     * data channel. Whatever the pump says about the password, the channel is probed - its own
     * success or refusal is what decides the outcome, not the FFC2 byte, because a pump with no
     * password rejects the byte yet still opens the channel to anyone.
     *
     * [gatt]+[btConnectionId] identify this client. Before every Bluetooth call the worker re-checks it
     * is still the live one, so a stale worker woken by `closeGatt` never touches a newer client. The
     * whole body is wrapped: this runs on a bare thread and an uncaught throw kills the process.
     */
    override fun onPasswordGate(gatt: BluetoothGatt, btConnectionId: Int) {
        try {
            synchronized(passwordReplyLock) { passwordReply = null }
            gateSerial = preferences.get(ApexStringKey.SerialNumber)
            gatePassword = preferences.get(ApexStringKey.PumpPassword)

            // Step 1: already locked out for this pump. Do not write FFC1 or FFE4.
            if (isPasswordLockout()) {
                aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: lockout in force - not opening the channel")
                postPasswordBlockedCard()
                if (apexBluetooth.isCurrent(gatt, btConnectionId)) apexBluetooth.disconnect()
                return
            }

            // Steps 2-3: nothing to send (not six digits, or this exact value was already rejected
            // while the channel still opened). Skip FFC1 and probe the channel; a locked pump then
            // fails the probe and locks out on the snapshot.
            val frame = ApexDriverLogic.passwordToSend(true, gatePassword, rejectedPassword)
            if (frame == null) {
                if (gatePassword == rejectedPassword)
                    aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: this password was already rejected - not sending it again")
                else
                    aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the stored password is not six digits - not sending anything")
                probePasswordDataChannel(gatt, btConnectionId)
                return
            }

            // Step 4: subscribe FFC2.
            if (destroyed || !apexBluetooth.isCurrent(gatt, btConnectionId)) return
            if (!apexBluetooth.subscribePasswordReply(gatt)) {
                aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: could not subscribe to the reply - probing the data channel")
                probePasswordDataChannel(gatt, btConnectionId)
                return
            }
            // Step 5: await the subscription. A timeout leaves the write outstanding, so the channel
            // must not be probed on top of it - drop the link instead. A non-success closes the op.
            when (val sub = apexBluetooth.awaitPasswordSubscription(PASSWORD_SUBSCRIBE_TIMEOUT)) {
                null                       -> {
                    aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the subscription was never confirmed - dropping the link")
                    if (apexBluetooth.isCurrent(gatt, btConnectionId)) apexBluetooth.disconnect()
                    return
                }

                BluetoothGatt.GATT_SUCCESS -> Unit
                else                       -> {
                    aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the subscription failed with status $sub - probing the data channel")
                    probePasswordDataChannel(gatt, btConnectionId)
                    return
                }
            }
            // Step 6: write FFC1.
            if (destroyed || !apexBluetooth.isCurrent(gatt, btConnectionId)) return
            if (!apexBluetooth.sendPasswordFrame(gatt, frame)) {
                aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: the write was not accepted - probing the data channel")
                probePasswordDataChannel(gatt, btConnectionId)
                return
            }
            // Step 7: await the FFC1 write. Same tri-state as the subscription.
            when (val wr = apexBluetooth.awaitPasswordWrite(PASSWORD_WRITE_TIMEOUT)) {
                null                       -> {
                    aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the write was never confirmed - dropping the link")
                    if (apexBluetooth.isCurrent(gatt, btConnectionId)) apexBluetooth.disconnect()
                    return
                }

                BluetoothGatt.GATT_SUCCESS -> Unit
                else                       -> {
                    aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the write failed with status $wr - probing the data channel")
                    probePasswordDataChannel(gatt, btConnectionId)
                    return
                }
            }
            // Wait for the pump's verdict, for the card only; the channel is probed regardless.
            synchronized(passwordReplyLock) {
                if (passwordReply == null) passwordReplyLock.waitMillis(PASSWORD_REPLY_TIMEOUT)
            }
            when (passwordReply) {
                ApexDriverLogic.PasswordReply.Accepted     -> aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: accepted by the pump")
                ApexDriverLogic.PasswordReply.Rejected     -> aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: rejected by the pump - the data channel decides what happens next")
                ApexDriverLogic.PasswordReply.ChangeAnswer -> aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the pump answered as if asked to CHANGE the password - the model is wrong")
                ApexDriverLogic.PasswordReply.Unknown      -> aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the pump answered something this driver does not understand")
                null                                       -> aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: no answer within $PASSWORD_REPLY_TIMEOUT ms")
            }
            // Step 8.
            probePasswordDataChannel(gatt, btConnectionId)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the connection-password step failed", e)
            if (apexBluetooth.isCurrent(gatt, btConnectionId)) apexBluetooth.disconnect()
        } finally {
            // Clear the gate flag only if this worker still owns it and FFE4 was not issued. After a
            // successful probe the flag belongs to the FFE4 callback; after any abort this is the clear.
            apexBluetooth.clearPasswordGateIfOwner(gatt, btConnectionId)
        }
    }

    /** Step 8: subscribe the data channel, unless a newer client took over. */
    private fun probePasswordDataChannel(gatt: BluetoothGatt, btConnectionId: Int) {
        if (!apexBluetooth.isCurrent(gatt, btConnectionId)) return
        // On acceptance the FFE4 callback owns promotion and the gate flag from here. On a stack
        // refusal there is no verdict from the pump, so no lockout - just drop the link.
        if (apexBluetooth.enableReadNotifications(gatt)) return
        aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the data-channel subscribe was refused by the stack - dropping the link")
        if (apexBluetooth.isCurrent(gatt, btConnectionId)) apexBluetooth.disconnect()
    }

    override fun currentPasswordReply(): ApexDriverLogic.PasswordReply? = passwordReply

    /**
     * The data channel was refused and the password was not accepted: lock out on the snapshot and warn
     * (card B), then drop the link. The lockout is armed only while the snapshot still describes the
     * pump the user is pointing at, so a mid-handshake edit does not re-arm it on a password already
     * replaced.
     */
    override fun onPasswordChannelRefused() {
        val liveHas = preferences.get(ApexBooleanKey.PumpHasPassword)
        val livePassword = preferences.get(ApexStringKey.PumpPassword)
        if (liveHas && livePassword == gatePassword) {
            lockoutSerial = gateSerial
            lockoutPassword = gatePassword
            lockoutConnectLogged = false
            aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the pump refused the data channel - locking out until the password changes")
            postPasswordBlockedCard()
        } else {
            aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: data channel refused but the password or toggle changed - not locking out")
        }
        apexBluetooth.disconnect()
    }

    /** The data channel was refused although the pump accepted the password: no lockout, no card - just drop it. */
    override fun onPasswordCccFailed() {
        aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the data channel was refused although the password was accepted - dropping the link")
        apexBluetooth.disconnect()
    }

    /** Whether the anti-hammer lockout is in force for the pump the user is pointing at now. */
    fun isPasswordLockout(): Boolean = ApexDriverLogic.isPasswordLockout(
        lockoutSerial, lockoutPassword,
        preferences.get(ApexStringKey.SerialNumber),
        preferences.get(ApexStringKey.PumpPassword),
        preferences.get(ApexBooleanKey.PumpHasPassword),
    )

    private fun postPasswordBlockedCard() {
        apexNotifications.post(
            ApexNotifications.PASSWORD_REJECTED,
            rh.gs(R.string.notification_pump_password_blocked),
            level = Notification.NORMAL,
        )
    }

    /**
     * Card A, read from the `onConnect` tail once the connection has finished: the pump is up, but it
     * did not accept the password. Only when the toggle is on; the reject card is armed only while the
     * live password still equals what the gate sent, so a field edited during `onConnect` does not get
     * the typo restored.
     */
    private fun applyPasswordCardsAfterConnect() {
        if (!preferences.get(ApexBooleanKey.PumpHasPassword)) return
        when (passwordReply) {
            ApexDriverLogic.PasswordReply.Accepted     -> apexNotifications.dismiss(ApexNotifications.PASSWORD_REJECTED)
            ApexDriverLogic.PasswordReply.Rejected     ->
                if (preferences.get(ApexStringKey.PumpPassword) == gatePassword) {
                    rejectedPassword = gatePassword
                    apexNotifications.post(
                        ApexNotifications.PASSWORD_REJECTED,
                        rh.gs(R.string.notification_pump_password_rejected),
                        level = Notification.NORMAL,
                    )
                }

            ApexDriverLogic.PasswordReply.ChangeAnswer ->
                aapsLogger.error(LTag.PUMPCOMM, "PASSWORD: the pump answered a CHANGE on a verify frame - the model is wrong")

            ApexDriverLogic.PasswordReply.Unknown, null ->
                aapsLogger.info(LTag.PUMPCOMM, "PASSWORD: password unanswered, data channel opened")
        }
    }

    /** Lift the lockout and the reject latch, and take any password card down. */
    private fun clearPasswordLockout() {
        lockoutSerial = null
        lockoutPassword = null
        rejectedPassword = null
        lockoutConnectLogged = false
        apexNotifications.dismiss(ApexNotifications.PASSWORD_REJECTED)
    }

    /**
     * The password, toggle or serial changed, so the old lockout no longer describes this pump. Lift it
     * and, unless stopped or already connected, start a fresh attempt - a pump the user just fixed, or
     * one the user turned the feature off for (a locked pump then does today's stock failure, intended).
     */
    private fun onPasswordSettingsChanged() {
        clearPasswordLockout()
        if (destroyed || manualDisconnect) return
        if (connectionStatus == ApexBluetooth.Status.CONNECTED) return
        if (!isDisconnectLoopRunning) {
            startConnection()
            spawnLoop()
        }
    }

    /**
     * Read the pump's fill history once per service run, for the log only, if the user asked for it.
     *
     * Off unless `ApexBooleanKey.ReadFillHistory` is on. This value has never been requested by this
     * driver and its record layout has never been captured, so how the pump answers it - or whether it
     * answers at all - is genuinely unknown. It runs at the very end of onConnect, where the command
     * queue has already been told the pump is ready, so it can hold the command lock in the exact
     * window where the loop wants to send a bolus or a TBR. Hence three guards, all of which must
     * hold:
     *
     *  * nothing waiting in the command queue, and nothing being executed - `isBusy()` is false for
     *    this driver, so the queue would happily block its single thread behind this read;
     *  * no AAPS bolus in flight (`pump.inProgressBolus`) - checked once at the call site for both
     *    reads, so it is the queue guard above that keeps this off a bolus started meanwhile. A bolus
     *    punched in on the pump keypad sets nothing in this driver and is not detected here;
     *  * [FILL_HISTORY_RESPONSE_TIMEOUT] to produce the FIRST record, not the 30 s a history read
     *    normally gets: a pump that supports the value answers at once, and a pump that ignores it
     *    must not stand in front of an insulin command.
     *
     * When a guard blocks it the flag is deliberately NOT set, so the read is attempted again on the
     * next connection instead of being lost for the life of the service.
     *
     * `noOptimizations = true` is load-bearing, not decoration: without it a silent pump would make
     * getValue tear the link down and reconnect, which is far worse than the read failing.
     */
    private fun readFillHistoryOnce() {
        if (!preferences.get(ApexBooleanKey.ReadFillHistory)) return
        if (commandQueue.size() != 0 || commandQueue.performing() != null) {
            aapsLogger.debug(LTag.PUMPCOMM, "FILL-HISTORY: the command queue is busy - trying again on the next connection")
            return
        }
        if (!fillHistoryRead.compareAndSet(false, true)) return
        aapsLogger.info(LTag.PUMPCOMM, "FILL-HISTORY: reading the pump's fill history once, for the log only")
        status.addAction(ApexDriverStatus.Action.GettingRefills, R.string.action_getting_refills)
        val response = try {
            getValue(GetValue.Value.FillHistory, noOptimizations = true, firstResponseTimeoutMs = FILL_HISTORY_RESPONSE_TIMEOUT)
        } finally {
            status.removeAction(ApexDriverStatus.Action.GettingRefills)
        }
        if (response == null) aapsLogger.info(LTag.PUMPCOMM, "FILL-HISTORY: the pump did not answer the fill history read")
        else aapsLogger.info(LTag.PUMPCOMM, "FILL-HISTORY: read finished, ${response.size} records")
    }

    /**
     * Read the pump's recent boluses - the ten-record ring, value 0x21.
     *
     * There is a second, longer series: value 0x01, the pump's whole bolus history, which is what the
     * maker's own app reads. This used to be reachable through an `isFullHistory` parameter that no
     * caller ever set, so the long read was dead code that looked live. It is not wired up because
     * nothing knows when it would be worth its cost: it returns far more records than the ring, and
     * every read here holds the command lock, and one of the four is on the connect path where an
     * insulin command may be waiting behind it. The ring
     * covers any gap short of many hours. If a real gap ever shows up in a log, that is the moment to
     * add 0x01 with a trigger - not before.
     */
    fun getBoluses(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getBoluses - $caller")

        if (pump.inProgressBolus?.lockHistory == true) {
            aapsLogger.info(LTag.PUMPCOMM, "Pump history is locked. Bolus is in progress.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.GettingBoluses, R.string.action_getting_boluses)
        val response = getValue(GetValue.Value.LatestBoluses)
        status.removeAction(ApexDriverStatus.Action.GettingBoluses)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBoluses caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    /**
     * Read the pump status.
     *
     * @param readV2 whether to also read StatusV2. StatusV2 holds nothing the driver needs in order
     *   to dose - only the exact battery voltage and the alarm sound length - so the paths that are
     *   about to send insulin skip it (see [checkPump]) instead of holding the command lock for
     *   another 10 s. Everything a decision depends on (alarms, reservoir, running basal, temp
     *   basal, max basal/bolus, pump lock, pump clock) comes from StatusV1.
     */
    fun getStatus(caller: String, optimize: Boolean = false, force: Boolean = false, readV2: Boolean = true): Boolean {
        if (!force && ApexDriverLogic.isStatusFresh(pump.lastStatusReceivedAt, System.currentTimeMillis())) {
            aapsLogger.debug(LTag.PUMPCOMM, "Status is already fresh, skipping unnecessary update.")
            return true
        }

        val hasV2 = readV2 && pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true

        aapsLogger.debug(LTag.PUMPCOMM, "getStatus - $caller")

        status.addAction(ApexDriverStatus.Action.GettingStatus,
             if (hasV2) rh.gs(R.string.action_getting_status_v, 1)
             else rh.gs(R.string.action_getting_status)
        )
        val responseV1 = getValue(GetValue.Value.StatusV1, noOptimizations = !optimize)
        if (!hasV2 || responseV1 == null) status.removeAction(ApexDriverStatus.Action.GettingStatus)

        if (responseV1 == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getStatus caller=$caller] V1 | Timed out while trying to communicate with the pump")
            return false
        }

        if (hasV2) {
            status.updateAction(ApexDriverStatus.Action.GettingStatus, rh.gs(R.string.action_getting_status_v, 2))
            val responseV2 = getValue(GetValue.Value.StatusV2, noOptimizations = true)
            status.removeAction(ApexDriverStatus.Action.GettingStatus)

            if (responseV2 == null) {
                // Deliberately NOT a failure. StatusV1 arrived moments ago and is already applied,
                // and StatusV2 adds only the battery voltage and the alarm sound length. Returning
                // false here turned a missing cosmetic frame into real damage: onConnect tears the
                // link down and restarts the ~25 s reconnect loop, and through checkPump it refuses
                // a bolus or a temp basal - and a refused temp basal makes the loop skip that
                // cycle's SMB as well. Measured on 2026-08-10: during a flaky radio window the pump
                // answered V1 every time and V2 not at all, and the driver spent 13 minutes
                // reconnecting instead of staying on the link it already had.
                aapsLogger.warn(
                    LTag.PUMPCOMM,
                    "[getStatus caller=$caller] V2 | Timed out - keeping the connection, StatusV1 carries everything we need"
                )
            }
        }

        return true
    }

    fun getBasalProfiles(caller: String): Map<Int, List<Double>>? {
        val ret = mutableMapOf<Int, List<Double>>()
        aapsLogger.debug(LTag.PUMPCOMM, "getBasalProfiles - $caller")

        status.addAction(ApexDriverStatus.Action.GettingBasalProfiles, R.string.action_getting_basal)
        val response = getValue(GetValue.Value.BasalProfiles)
        status.removeAction(ApexDriverStatus.Action.GettingBasalProfiles)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBasalProfiles caller=$caller] Timed out while trying to communicate with the pump")
            return null
        }

        for (i in response) {
            require(i is BasalProfile)
            ret[i.index] = i.rates.map { it * 0.025 }
        }

        ret[USED_BASAL_PATTERN_INDEX]?.let { pump.cachedBasalProfile = it }

        return ret
    }

    //////// Public values

    val connectionStatus: ApexBluetooth.Status
        get() = apexBluetooth.status

    //////// Pump commands handlers

    @Synchronized
    private fun onEmulatedSuspend() {
        Thread {
            SystemClock.sleep(2500)
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnecting)
            while (commandQueue.size() != 0) {
                aapsLogger.debug(LTag.PUMP, "Waiting for queue to end")
                SystemClock.sleep(250)
            }
            aapsLogger.debug(LTag.PUMP, "Disconnecting")
            disconnect()
        }.start()
        SystemClock.sleep(100)
    }

    private fun onBolusProgress(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus progress $dose")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            val isSMB = it.detailedBolusInfo.bolusType == BS.Type.SMB

            status.updateOrAddAction(
                if (isSMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing,
                rh.gs(
                    if (isSMB)
                        R.string.action_bolusing_smb
                    else
                        R.string.action_bolusing,
                    it.currentDose,
                    it.requestedDose
                )
            )

            bolusProgressData.updateProgress((it.currentDose / it.requestedDose * 100).roundToInt(), TextRef.Literal(rh.gs(R.string.status_delivering, dose)), PumpInsulin(it.currentDose))
        }
    }

    private fun onBolusCompleted(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus completed")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )
            bolusProgressData.updateProgress(100, TextRef.Literal(rh.gs(R.string.status_delivered, dose)), PumpInsulin(dose))

            // Request new bolus history to fixup bolus ID.
            Thread { getBoluses("ApexService-onBolusCompleted") }.start()
        }
    }

    /**
     * Wind up a bolus the pump stopped or the user cancelled.
     *
     * [deliveryStopConfirmed] says whether the reason is proof the motor stopped - see
     * [ApexDriverLogic.stopsDelivery]. It only guards the record that matches the pump's own history
     * entry to the row AAPS already wrote; everything else runs either way, because leaving the
     * tracking state behind would refuse every later bolus.
     */
    private fun onBolusFailed(cancelled: Boolean = false, deliveryStopConfirmed: Boolean = true) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus failed (cancelled? $cancelled, motor stop confirmed? $deliveryStopConfirmed)")
        pump.inProgressBolus?.let {
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )

            if (cancelled) {
                it.cancelled = true
                bolusProgressData.updateProgress((it.currentDose / it.requestedDose * 100).roundToInt(), TextRef.Literal(rh.gs(R.string.status_bolus_cancelled)), PumpInsulin(it.currentDose))
            }

            if (it.currentDose >= 0.025) {
                // Request new bolus history to fixup bolus ID and delivered amount.
                Thread { getBoluses("ApexService-onBolusCompleted") }.start()
            } else {
                aapsLogger.debug(LTag.PUMPCOMM, "bolus ended with no progress reported (motor stop confirmed? $deliveryStopConfirmed)")
                synchronized(it) {
                    it.failed = true
                    // Carried with the flag, because the flag on its own cannot tell "the pump did not
                    // deliver" from "nobody can say whether it delivered". The block below keeps the
                    // pending sync on the unproven ending for that very reason, and the card that warns
                    // about an unaccounted dose has to be raised on the same ending.
                    it.deliveryStopProven = deliveryStopConfirmed
                    it.notifyAll()
                }
                SystemClock.sleep(100)
                // The record is about to stop being the live one, and with it goes the only handle on
                // any "may be incomplete" card for this bolus. Keep it when the pump's own history entry
                // is still coming, because that entry is the only thing that can say whether such a card
                // is true. Without this the card outlived its proof - it went on saying nothing was
                // delivered while the entry that arrived moments later showed the full dose had gone in,
                // and a warning like that invites a second dose on top of insulin already on board.
                //
                // Kept whether or not a card is standing right now, and that is not laziness. The card
                // for this ending is raised by the waiting `deliverTreatment` call, which the notifyAll
                // above has only just woken; asking "is there a card yet" here would be a race against a
                // thread that still has to compose and post one. A record kept with no card is harmless
                // - settling it either finds nothing to take down, or finds the entry proves a partial
                // delivery and raises the card the waiter never got to.
                //
                // BEFORE the line below, which is the gate `onBolusEntry` opens on: it enters the
                // recovery block only when there is no live bolus. Published after it, the record could
                // still be missing when a frame worker walks straight in, consumes the pending slot and
                // finds nothing to settle - and the entry that would have taken the card down is then
                // spent.
                //
                // Only the unproven ending. The other one clears the pending slot, and
                // `savePendingBolusSync` drops the record along with it, so keeping it there would
                // achieve nothing.
                if (!deliveryStopConfirmed) abandonedBolusWithWarning = it
                pump.inProgressBolus = null
                if (deliveryStopConfirmed) {
                    savePendingBolusSync(null) // nothing was delivered, so there is no history entry to match
                } else {
                    // No progress frame arrived, but the alarm that ended the bolus says nothing about
                    // the motor - a keypad fault, a screen fault, a clock fault, or a code the table
                    // does not know. "No progress reported" is not "nothing delivered": drop the record
                    // here and a history entry that does exist finds no temporary id to attach to, so
                    // core inserts a SECOND row beside the one written when the bolus was sent and the
                    // insulin is counted twice for good. A kept record carries a smaller risk, not no
                    // risk: PendingBolusSync.matches accepts an entry within about a second, or about
                    // a minute, of this bolus and within 0.11 U of its size, and the pump stamps every
                    // entry at second 59 of its minute - so a bolus of the same size given on the
                    // keypad in the same or the neighbouring minute can claim it. That is the better
                    // of the two risks: it costs one misattributed row in a narrow window, against a
                    // duplicated row for good. onBolusEntry drops the record once it goes stale.
                    aapsLogger.info(LTag.PUMP, "Keeping pending bolus sync: the alarm is not proof that the motor stopped")
                }
            }
        }
    }

    private fun onCommandResponse(response: CommandResponse) {
        aapsLogger.debug(LTag.PUMPCOMM, "got command response - ${response.code.name} / ${response.dose}")
        when (response.code) {
            CommandResponse.Code.Accepted, CommandResponse.Code.Invalid -> {
                if (!commandResponse.waiting) return
                commandResponse.response = response
                commandResponse.waiting = false
                synchronized(commandResponse) {
                    commandResponse.notifyAll()
                }
            }
            CommandResponse.Code.StandardBolusProgress -> onBolusProgress(response.dose * 0.025)
            CommandResponse.Code.ExtendedBolusProgress -> return
            CommandResponse.Code.Completed             -> onBolusCompleted(response.dose * 0.025)
            else                                       -> return
        }
    }

    private fun onAlarmsChanged(update: ApexPump.StatusUpdate) {
        val current = update.current.alarms
        // What is NEW, not "was the list empty before". The old test announced nothing at all once any
        // alarm was standing, and a low reservoir stands for hours — so an occlusion or an empty
        // cartridge arriving behind it produced no notification, no Nightscout announcement, and never
        // reached the bolus-failed branch below.
        val added = newAlarms(update.previous?.alarms, current)

        // One card per standing alarm, each with its own handle. While every alarm went to the one
        // shared PUMP_ERROR id the second one replaced the first: its text vanished and the first
        // alarm's siren stopped with it, because the replacement carried no sound. Dismissing is now
        // per card, so nothing this driver does can take down a card the rest of the app raised.
        synchronized(alarmCardLock) { alarmCards.sync(current, added) }

        if (added.isNotEmpty()) {
            var anyUrgent = false
            // Whether any new alarm is proof the motor stopped. The trigger below deliberately stays
            // the urgent catch-all: narrowing it would leave inProgressBolus and lockHistory set
            // whenever the pump stops on a code outside the narrow list, and nothing else clears them
            // while the link stays up - so every later bolus would be refused as "already in progress"
            // and the history read that carries the true delivered amount would never run. What the
            // narrow list decides is only whether onBolusFailed may throw away the record that ties
            // the pump's own history entry to the row AAPS already wrote.
            var anyDeliveryStop = false

            for (alarm in added) {
                if (ApexDriverLogic.isUrgent(alarm.type)) anyUrgent = true
                if (ApexDriverLogic.stopsDelivery(alarm.type)) anyDeliveryStop = true
                pumpSync.insertAnnouncement(
                    error = alarmLabel(alarm),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                )
            }

            if (anyUrgent && pump.isBolusing) {
                // Pump sends early heartbeat while bolusing if there's an error while bolusing.
                aapsLogger.error(LTag.PUMP, "Bolus has failed! (motor stop confirmed by the code? $anyDeliveryStop)")
                Thread { onBolusFailed(deliveryStopConfirmed = anyDeliveryStop) }.start()
            }
        }
    }

    /**
     * The line a card and a Nightscout announcement both carry: the alarm's name, its raw code, and -
     * when the maker's own texts confirm one - the E-number the pump prints on its own screen.
     *
     * The user is often looking at the pump and the phone at the same time, and the two name the same
     * fault differently. "Pressure is not normal (0x20B, E23)" can be matched to the pump display;
     * "Pressure is not normal (0x20B)" cannot. Two templates rather than one built by hand, because
     * most alarms have no E-number and must not be left with an empty bracket: the maker gives one to
     * the second alarm family only, so "Line is blocked (0x108)" is complete as it stands.
     */
    private fun alarmLabel(alarm: PumpAlarm): String {
        val screenCode = alarm.screenCode
        return if (screenCode == null) rh.gs(R.string.alarm_label, alarmName(alarm), alarm.code)
        else rh.gs(R.string.alarm_label_with_screen_code, alarmName(alarm), alarm.code, screenCode)
    }

    /**
     * The text shown for one pump alarm.
     *
     * Exhaustive on purpose, with no `else` arm: a new entry in [Alarm] then fails to COMPILE until
     * someone has decided what it should say. The old catch-all told the user "Unknown error
     * <KotlinIdentifier>, report to developers" for nine codes this driver does name - which reads as
     * a driver bug, asks for a report about something already known, and put an English identifier
     * inside a Russian sentence that also went to the database and to Nightscout.
     *
     * None of these strings carry the raw code: the alarm-label templates append it to every alarm,
     * so a mis-mapped code stays diagnosable from a screenshot.
     */
    private fun alarmName(alarm: PumpAlarm): String = when (alarm.type) {
        // Three separate cartridge and line faults, three separate texts. They were pooled into one
        // "Occlusion" before, so a pump asking for a new cartridge told the user to hunt for a
        // blockage. The maker's own dictionary separates them: 0x108 "Pipe blockage", 0x10D "Empty
        // Reservoir - change reservoir", 0x10F "No reservoir".
        Alarm.NoDelivery                 -> rh.gs(R.string.apex_alarm_occlusion)
        Alarm.ReservoirEmpty             -> rh.gs(R.string.apex_alarm_reservoir_empty)
        Alarm.NoReservoir                -> rh.gs(R.string.apex_alarm_no_reservoir)
        Alarm.LowReservoir               -> rh.gs(R.string.alarm_w_reservoir_low)
        Alarm.DeadBattery                -> rh.gs(R.string.alarm_battery_dead)
        Alarm.LowBattery                 -> rh.gs(R.string.alarm_w_battery_low)
        Alarm.BatteryError               -> rh.gs(R.string.alarm_battery_error)
        Alarm.CheckGlucose               -> rh.gs(R.string.alarm_check_bg)
        // The vendor's own label for this code. A captured session has it standing while the pump was
        // reporting a low cartridge, so the text sends the user to the pump screen instead of
        // asserting a meaning the evidence does not support.
        Alarm.ButtonError                -> rh.gs(R.string.alarm_button_error)
        Alarm.TDDLimitTriggered          -> rh.gs(R.string.alarm_tdd_limit)
        Alarm.MotorError                 -> rh.gs(R.string.alarm_motor_error)
        Alarm.MotorAbnormal              -> rh.gs(R.string.alarm_motor_abnormal)
        Alarm.MotorPowerAbnormal         -> rh.gs(R.string.alarm_motor_power_abnormal)
        Alarm.EncoderError               -> rh.gs(R.string.alarm_encoder_error)
        Alarm.BolusOrBasalDoseAbnormal   -> rh.gs(R.string.alarm_dose_abnormal)
        Alarm.PressureAbnormal           -> rh.gs(R.string.alarm_pressure_abnormal)
        Alarm.FRAMError                  -> rh.gs(R.string.alarm_fram_error)
        Alarm.ScreenError                -> rh.gs(R.string.alarm_screen_error)
        Alarm.TimeError                  -> rh.gs(R.string.alarm_time_error)
        Alarm.TimeAnomalyError           -> rh.gs(R.string.alarm_time_anomaly)
        Alarm.ClockError                 -> rh.gs(R.string.alarm_clock_error)
        Alarm.ResetError                 -> rh.gs(R.string.alarm_reset_error)
        Alarm.CommunicationError         -> rh.gs(R.string.alarm_communication_error)
        Alarm.ConnectionAnomaly          -> rh.gs(R.string.alarm_connection_anomaly)
        // The vendor's table has these two as placeholders. Nothing on the wire says what they mean,
        // so the text says exactly that and leaves the number to speak.
        Alarm.Reserved1, Alarm.Reserved2 -> rh.gs(R.string.alarm_reserved_code)
        // Never reaches a card: both decoders drop a NoError slot before it becomes an alarm. Named
        // anyway, so this `when` needs no `else` to compile.
        Alarm.NoError                    -> rh.gs(R.string.alarm_no_error)
        Alarm.Unknown                    -> rh.gs(R.string.alarm_unknown_error_name)
    }

    /**
     * Put one pump alarm on screen, with a sound only if it belongs on the alarm tier.
     *
     * [sound] is false when the card is merely being put back after the user cleared it: a re-post
     * carrying the sound would start the ramp again on the next status frame — and a code the pump
     * only clears on a physical acknowledge would be impossible to silence from the phone at all. The
     * card is what has to survive being cleared; the ring is not.
     *
     * [into] is the card's own handle when it is being put back, or null for an alarm that has no card
     * yet. A card is a notification id here, so putting one back has to land on that id.
     *
     * The returned handle is what takes this very card down again when its alarm clears - see
     * [ApexAlarmCards].
     */
    private fun postAlarm(alarm: PumpAlarm, sound: Boolean, into: ApexNotifications.Handle?): ApexNotifications.Handle {
        val urgent = ApexDriverLogic.isUrgent(alarm.type)
        return apexNotifications.postAlarmCard(
            text = alarmLabel(alarm),
            level = if (urgent) Notification.URGENT else Notification.NORMAL,
            soundRes = if (sound && ApexDriverLogic.ringsFor(alarm.type)) CoreUiR.raw.alarm else null,
            into = into,
        )
    }

    private fun onBasalChanged(update: ApexPump.StatusUpdate) {
        val suspended = update.current.basal == null
        // Act on the change of state, not on every status frame. This ran once per frame, so a suspended
        // pump re-posted the notification about once a minute — Android re-alerts on each post, with no
        // setOnlyAlertOnce — and ran its side effect alongside it for as long as the suspension lasted.
        // A repeating alert is worse than none: people mute the channel, and then the real alarms go
        // with it.
        // A null previous is the first frame after an app start: the state is new to us, so report it once.
        // The card is raised again if it is gone while the pump is still suspended — it can be swiped
        // away in the notification shade, or dismissed on the overview — otherwise a pump that stopped
        // delivering overnight would never be mentioned again.
        val wasSuspended = update.previous?.let { it.basal == null }
        val cardStanding = apexNotifications.isOnScreen(Notification.PUMP_SUSPENDED)
        if (suspended == wasSuspended && (!suspended || cardStanding)) return
        // Reaching here with the state unchanged means only one thing: the user cleared the card. Put it
        // back without the sound and without repeating the side effect — otherwise the ramp they just
        // stopped starts again on the next frame.
        val reRaise = suspended == wasSuspended

        if (suspended) {
            apexNotifications.post(
                Notification.PUMP_SUSPENDED,
                rh.gs(R.string.notification_pump_is_suspended),
                level = if (pump.isBolusing) Notification.URGENT else Notification.NORMAL,
                // Suspended while a bolus was running means the bolus stopped. That has to be audible.
                soundRes = if (pump.isBolusing && !reRaise) CoreUiR.raw.alarm else null,
            )
            // Kept as it was, only no longer once a minute. Be aware of what it is: CommandLoadEvents
            // dispatches on the pump type and Apex is not one of the three it knows, so the command
            // itself does nothing — the cost is the queue round trip it forces, on this thread.
            if (!reRaise) serviceScope.launch { commandQueue.loadEvents() }
        } else {
            apexNotifications.dismiss(Notification.PUMP_SUSPENDED)
        }
    }

    /**
     * Keep AAPS IOB in line with a TBR that started or ended on the pump itself.
     *
     * AAPS-started TBRs are already in the database before this status frame, so a matching
     * pump TBR is left alone. See [ApexDriverLogic.tbrSyncAction].
     *
     * Semantically invalid TBR bytes stay off this path: [ApexPump.StatusUpdate.tbrDecodeInvalid]
     * skips sync. Display may still show the last good TBR from [ApexDriverLogic.resolvedTbr].
     */
    private fun onTbrChanged(update: ApexPump.StatusUpdate, epoch: Int) =
        reconcileTbr(update.current.tbr, update.tbrDecodeInvalid, epoch)

    /**
     * Insulin concentration of the running profile, for converting pump-native rates into the
     * units PumpSync stores. Falls back to 1.0 with no profile - then no TBR row can be written
     * anyway ([PumpSync] refuses without a profile).
     */
    private fun insulinConcentration(): Double = insulinConcentrationOrNull() ?: 1.0

    /**
     * The running profile's insulin concentration, or `null` when no profile is active.
     *
     * [insulinConcentration] answers 1.0 there, which is right for a conversion feeding a PumpSync
     * write - that write is refused without a profile anyway. It is wrong for a comparison against a
     * user's threshold: 1.0 is a guess, and on U50 or U200 a guessed conversion moves the level a
     * warning fires at. Callers that compare take this one and switch the comparison off instead.
     */
    private fun insulinConcentrationOrNull(): Double? =
        runBlocking { profileFunction.getProfile()?.insulinConcentration() }

    private fun reconcileTbr(pumpTbr: ApexPump.TBR?, tbrDecodeInvalid: Boolean, epoch: Int) {
        if (tbrDecodeInvalid) {
            aapsLogger.warn(LTag.PUMP, "Ignoring semantically invalid TBR fields in StatusV1")
            return
        }
        synchronized(tbrSyncLock) {
            if (epoch != tbrSyncEpoch) return
            val now = System.currentTimeMillis()
            val concentration = insulinConcentration()
            val expected = pumpSync.expectedPumpState().temporaryBasal
            lastAapsTbr = expected ?: lastAapsTbr
            when (val action = ApexDriverLogic.tbrSyncAction(pumpTbr, expected, now, lastAapsTbr, concentration)) {
                is ApexDriverLogic.TbrSyncAction.None  -> Unit
                is ApexDriverLogic.TbrSyncAction.Stop  -> {
                    aapsLogger.info(LTag.PUMP, "Pump TBR ended - stopping the AAPS TBR")
                    lastAapsTbr = (expected ?: lastAapsTbr)?.let { ApexDriverLogic.tbrDroppedAt(it, now) }
                    val closed = pumpSync.syncStopTemporaryBasalWithPumpId(
                        timestamp = now,
                        endPumpId = now,
                        pumpType = PumpType.APEX_TRUCARE_III,
                        pumpSerial = apexDeviceInfo.serialNumber,
                    )
                    if (closed) onTbrSyncAccepted()
                    else logTbrSyncRefused(
                        source = "reconcileTbr stop",
                        refusal = ApexDriverLogic.tbrStopRefusal(hadRunningRow = expected != null),
                        facts = "stop=$now expectedRowBefore=${expected != null}",
                    )
                }
                is ApexDriverLogic.TbrSyncAction.Start -> {
                    aapsLogger.info(
                        LTag.PUMP,
                        "Pump TBR ${action.rate}${if (action.isAbsolute) "U/h" else "%"} " +
                            "for ${action.durationMs / 60000}min - syncing to AAPS"
                    )
                    val profileRunning = runBlocking { profileFunction.getProfile(action.timestamp) != null }
                    val written = pumpSync.syncTemporaryBasalWithPumpId(
                        timestamp = action.timestamp,
                        rate = action.rate,
                        duration = action.durationMs,
                        isAbsolute = action.isAbsolute,
                        type = PumpSync.TemporaryBasalType.NORMAL,
                        pumpId = action.timestamp,
                        pumpType = PumpType.APEX_TRUCARE_III,
                        pumpSerial = apexDeviceInfo.serialNumber,
                    )
                    if (written) onTbrSyncAccepted()
                    else logTbrSyncRefused(
                        source = "reconcileTbr start",
                        refusal = ApexDriverLogic.tbrStartRefusal(profileRunning),
                        facts = "rate=${action.rate} absolute=${action.isAbsolute} " +
                            "duration=${action.durationMs / 60000}min pumpId=${action.timestamp}",
                    )
                    rememberLastAapsAfterWrite(
                        written = written,
                        timestamp = action.timestamp,
                        durationMs = action.durationMs,
                        // The synthetic fallback row mimics the database row, which stores
                        // absolute rates multiplied by the insulin concentration.
                        rate = if (action.isAbsolute) action.rate * concentration else action.rate,
                        isAbsolute = action.isAbsolute,
                        type = PumpSync.TemporaryBasalType.NORMAL,
                    )
                }
            }
        }
    }

    /**
     * Report a temporary basal the pump accepted but core did not store.
     *
     * Both TBR paths report success to the loop on the pump's own Accepted alone - `temporaryBasal`
     * computes the sync result but does not return it, and the cancel path discarded it without even
     * a log line. So a refused write left the loop believing in a temporary basal the database does
     * not hold, or a cancel that did not close the open row, with nothing anywhere to count.
     *
     * This does not change either return value - that is a dosing decision and needs the pump. It
     * makes the refusal countable first: one greppable line per event, at error level, which is the
     * only level that is not gated by the user's log settings.
     *
     * Facts only. The consequence depends on which row core does hold afterwards, and the driver
     * cannot see that from here without a second query that would itself be a guess.
     *
     * One line per episode, not per attempt. The reconcile path retries on every status frame, so a
     * temporary basal core will not take would otherwise write an ungated error line every two
     * minutes for as long as it runs - the opposite of countable. The same source and reason is one
     * episode until a write succeeds and [onTbrSyncAccepted] clears it.
     */
    private fun logTbrSyncRefused(source: String, refusal: ApexDriverLogic.TbrSyncRefusal, facts: String) {
        val episode = "$source|${refusal.name}"
        if (episode == lastTbrRefusalEpisode) return
        lastTbrRefusalEpisode = episode
        aapsLogger.error(LTag.PUMP, "TBRSYNC-REFUSED [$source] reason=${refusal.name} $facts")
    }

    /** Cleared by any temporary basal write core accepts, so the next refusal is a new episode. */
    private fun onTbrSyncAccepted() {
        lastTbrRefusalEpisode = null
    }

    /**
     * Update [lastAapsTbr] after a PumpSync TBR write. Only invent a synthetic row when the write
     * succeeded and SQL does not yet show it. A failed write clears [lastAapsTbr] so the next
     * status frame can Start and retry, including after cancel-then-set at the same rate.
     *
     * Call only under [tbrSyncLock].
     */
    private fun rememberLastAapsAfterWrite(
        written: Boolean,
        timestamp: Long,
        durationMs: Long,
        rate: Double,
        isAbsolute: Boolean,
        type: PumpSync.TemporaryBasalType,
    ) {
        val expectedAfter = pumpSync.expectedPumpState().temporaryBasal
        lastAapsTbr = when {
            expectedAfter != null -> expectedAfter
            written -> PumpSync.PumpState.TemporaryBasal(
                timestamp = timestamp,
                duration = durationMs,
                rate = rate,
                isAbsolute = isAbsolute,
                type = type,
                id = 0L,
                pumpId = timestamp,
            )
            else -> null
        }
    }

    /**
     * Read StatusV1 after set/cancel, reconcile the TBR on this thread, then bump [tbrSyncEpoch].
     * Does not run full [onStatusV1]: that can wait on the command queue and deadlock this thread.
     * Does not touch [ApexPump] either: the same frame reaches [onStatusV1] on its own worker
     * thread, which applies it exactly once - so the diff handlers there see the real changes.
     * That worker's own TBR pass is dropped by the epoch bump below.
     * Heartbeat workers that captured the previous epoch are dropped.
     * Call only under [tbrSyncLock].
     */
    private fun readStatusV1ForTbrCommand() {
        status.addAction(ApexDriverStatus.Action.GettingStatus, rh.gs(R.string.action_getting_status))
        val responseV1 = getValue(GetValue.Value.StatusV1, noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.GettingStatus)
        val v1 = responseV1?.firstOrNull() as? StatusV1
        if (v1 != null) {
            // Decode the TBR fields WITHOUT applying the frame to ApexPump. The same frame is also
            // dispatched to onStatusV1 on its own worker thread; applying it here as well made the
            // second updateFromV1 see previous == current, so the diff-based handlers (alarms,
            // suspend, basal, settings) could permanently miss whatever this frame carried.
            val decoded = ApexDriverLogic.decodeStatusTbr(
                activeRaw = v1.temporaryBasalActiveRaw,
                isAbsoluteRaw = v1.temporaryBasalRateIsAbsoluteRaw,
                rateRaw = v1.temporaryBasalRate,
                durationMinutes = v1.temporaryBasalRateDuration,
                elapsedMinutes = v1.temporaryBasalRateElapsed,
            )
            val pumpTbr = (decoded as? ApexDriverLogic.DecodedStatusTbr.Running)?.tbr
            reconcileTbr(pumpTbr, !ApexDriverLogic.shouldReconcileDecodedTbr(decoded), tbrSyncEpoch)
        }
        tbrSyncEpoch++
    }

    private fun onSettingsChanged(update: ApexPump.StatusUpdate) {
        if (pump.settingsAreUnadvised && preferences.get(ApexDoubleKey.MaxBasal) != 0.0 && preferences.get(ApexDoubleKey.MaxBolus) != 0.0) updateSettings("ApexService-onSettingsChanged")
        if (update.current.currentBasalPattern != USED_BASAL_PATTERN_INDEX) updateBasalPatternIndex(USED_BASAL_PATTERN_INDEX, "ApexService-onSettingsChanged")
    }

    private fun onBatteryChanged(update: ApexPump.StatusUpdate) {
        val cur = update.current
        update.previous?.let { old ->
            // Never compare across two different scales. The stored level falls back to the pump's own
            // coarse gauge (Dead/Low/Medium/High/Full -> 0/25/50/75/100) whenever no exact value exists
            // yet or the last one has gone stale — see ApexPump.batteryLevelForV1 — and on a 4.11+ pump
            // the V1 frame that writes that fallback lands about 1.6 s before the V2 frame that runs this
            // check. Comparing an exact percentage against a coarse one can invent a swap that never
            // happened: a gauge reading Medium(50) against a real 81 % gives 81 - 26 > 50 and would write
            // a battery change on a plain app start, with nobody having touched the battery.
            // Only the MIXED case is skipped. Two coarse readings compare fine, and that is the only kind
            // a 4.9/4.10 pump ever produces — refusing those as well would quietly switch the feature off
            // for every pump that has no StatusV2, and for anyone with the calculated percentage turned off.
            if (old.batteryLevel.approximate != cur.batteryLevel.approximate) return
            // Percentage became higher - battery was changed. Read on the UNCAPPED figures - the raw
            // voltage map, taken before the pump's coarse gauge is applied as a ceiling on what gets
            // published: a cap must neither invent a swap nor hide one.
            //  - it must not invent one, because the cap lowers the published percentage without the
            //    cell having changed, so releasing it restores that number by up to the whole cap
            //    height - exactly the rise this looks for. The uncapped figure never moved.
            //  - it must not hide one, and that is why both figures cannot be required to rise
            //    together: when the gauge lags the new cell by a frame, the uncapped figure does all
            //    its rising on the first post-swap V2, while the gauge is still capping the
            //    published one; by the time the gauge releases, the uncapped figure is already high
            //    and no longer rising. Requiring both in one comparison logs the swap never.
            // That raw map is the figure this compared before the cap existed everywhere except under
            // a Dead gauge, where the map used to zero itself and nothing could reach this test.
            // Carrying it through the Dead state is deliberate: it is what makes a swap visible on
            // the first post-swap V2 while the gauge still lags (see CommandsTest.
            // pump_batterySwapIsDetectableEvenWhenTheGaugeLagsAFrame).
            // What is left is the heuristic's own long-standing weakness, untouched here: a voltage
            // swing of more than 26 points between two frames reads as a swap.
            if (cur.batteryLevel.uncappedPercentage - 26 > old.batteryLevel.uncappedPercentage && preferences.get(ApexBooleanKey.LogBatteryChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.PUMP_BATTERY_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged battery change")
            }
        }
    }

    /**
     * Log a cartridge change, and warn while the cartridge is low.
     *
     * [lowReservoirThreshold] is the pump's own setting out of the same status frame, in whole units.
     */
    private fun onReservoirChanged(update: ApexPump.StatusUpdate, lowReservoirThreshold: Int) {
        val cur = update.current
        update.previous?.let { old ->
            // Reservoir level became higher - insulin was changed.
            if (cur.reservoirLevel - 2 > old.reservoirLevel && preferences.get(ApexBooleanKey.LogInsulinChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.INSULIN_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged insulin change")
            }
        }

        // AAPS has no low-reservoir alert of its own: the two reservoir levels in core only tint the
        // overview status light and the watch face, and never post anything. So the pump's alarm code
        // was the ONLY path to a message about a low cartridge - and a session was captured where the
        // pump reported a code that does not decode to LowReservoir while the cartridge really was
        // below the pump's own threshold, so the user was told nothing about it at all.
        //
        // The pump's own history settles how far that goes: read in full on 2026-08-31 it held 66
        // records back to 2026-05-05, and not one of them is a low-reservoir alarm, over a window of
        // many cartridges with the pump set to warn at 20 U and at two hours left. So this block is
        // not a backstop behind the pump's warning - it is the only low-cartridge warning AAPS raises
        // by itself. (A user can build one by hand out of the `TriggerReservoirLevel` automation
        // trigger, but nothing warns out of the box without it.) Do not remove this as duplicated by
        // the pump, and do not weaken it because the pump "should" also alarm.
        //
        // The level compared against is the pump's own setting, out of the same frame, so the card
        // appears where the user set it on the pump. It deliberately carries no number: a card is
        // built once and is not rewritten while it stands, so a number would freeze at the crossing
        // and still read 20 U on an almost empty cartridge. The live value is on the overview row.
        // Two levels and two cards, decided independently - see the note further down for why they
        // are not a choice between one and the other. The lower one exists because the card above is
        // silent and is told once per crossing, so a cartridge that kept draining after it was never
        // mentioned again: measured on 2026-08-31 the low card went up at 16:09:53 and the pump
        // stopped delivering 4 h 36 min later with nothing said in between, while the pump itself
        // raised no low-reservoir alarm at any point in 390 status frames.
        //
        // The critical number is [IntKey.OverviewResCritical], which the user already picked for the
        // red reservoir tint on the overview, rather than one this driver invents. That preference is
        // in patient-side insulin units, so the cartridge reading has to be multiplied by the
        // profile's insulin concentration before it is compared - the overview and the watch face
        // both do exactly that. The pump's own threshold beside it is a number set ON the pump and
        // stays in the pump's units. Without the conversion the loud card would wait for half the
        // insulin the user asked for on U50, and fire at twice the level on U200.
        //
        // Reusing that preference has a consequence worth knowing before changing anything here. It
        // was a display setting - the red tint on the overview and the watch face - and it is now
        // also the level a siren fires at, with no separate switch of its own: the way to stop the
        // siren is to set it to 0, which takes the red tint with it. It is also declared
        // `SyncDirection.Bidirectional`, so a follower phone that nudges its own tint threshold
        // pushes the new value to the master, where this driver reads it. A phone with no pump can
        // therefore move an alarm level on the phone that has one. Deliberate for now - firing where
        // the number already turns red is the point, and an Apex-only threshold would be a second
        // place to keep in step - but a driver-local switch belongs here if that surprises anyone.
        //
        // With no running profile there is no conversion to make, and a guessed 1.0 would move the
        // level the siren fires at for exactly the users the conversion exists for. The overview and
        // the watch face both refuse to compare there rather than show a mis-scaled figure; here the
        // answer is null, and the block below leaves that card exactly as it is. The pump's own low
        // card needs no conversion and carries on either way.
        val concentration = insulinConcentrationOrNull()
        val criticalThreshold = preferences.get(IntKey.OverviewResCritical)
        val levelIu = concentration?.let { cur.reservoirLevel * it }
        val warnings = ApexDriverLogic.reservoirWarnings(
            levelPumpU = cur.reservoirLevel,
            pumpLowThresholdPumpU = lowReservoirThreshold,
            levelIu = levelIu,
            criticalThresholdIu = criticalThreshold,
        )
        // The pump's own low-reservoir alarm already has a card of its own (see [alarmCards]), and two
        // cards for one condition are worse than one. Folded into the state instead of guarding the
        // whole block: a pump alarm arriving behind our card must still take our card down, and the
        // state must keep moving while it stands, or acknowledging the pump alarm would post a fresh
        // card and changing the cartridge would leave a stale one.
        val pumpReportsItself = cur.alarms.any { it.type == Alarm.LowReservoir || it.type == Alarm.NoReservoir }
        // The loud card follows the LEVEL and nothing else. It is deliberately NOT gated on the
        // pump's own empty-cartridge alarm, tempting as that is to avoid two cards: the alarm slots
        // are read raw from each frame and they flicker - the driver documents one captured frame
        // with an empty slot list in the middle of a standing alarm, which is why the delivery-stop
        // latch next door waits for two clean frames - and the user acknowledging that alarm on the
        // keypad clears them for good. Either would drive this state false and then true again and
        // ring a second siren for a cartridge whose level never moved. The level does not flicker.
        //
        // Two cards is the price, and it is small: by the time the pump calls the cartridge empty
        // this card has been standing since the user's own level was crossed, so no second siren
        // comes from here - the pump's alarm rings on its own account, which it would anyway.
        //
        // The two cards of this block are independent for the same reason. Choosing between them
        // meant working out whether a card missing from the screen was the user silencing it or this
        // block taking it down itself, and every attempt at that told the two apart wrongly in some
        // case.
        //
        // What can take these cards down in this version: only the user. `NotificationStore` has no
        // mute-all and no silence - a card leaves it when the user dismisses it on the overview or
        // swipes its Android notification away, and that dismiss stops its sound with it. So nothing
        // in the app empties this block behind the user's back, whatever the critical level is set to
        // relative to the pump's own (the core default is 10 U against 20 U on the captured pump).
        // The overview reservoir row is red throughout either way, since it reads the same preference.
        val showCritical = warnings.critical == true
        val show = warnings.low && !pumpReportsItself

        // Under a lock, not just a volatile field: every status object is processed on its own worker
        // thread, so two frames can be in here at once and a plain read-modify-write can leave the
        // card contradicting the level. onDestroy takes the same lock around its own dismiss.
        synchronized(reservoirCardLock) {
            // A frame handed to the worker pool before onDestroy still runs after it - shutdown() lets
            // submitted tasks finish - and onDestroy takes this card down. The check belongs INSIDE
            // the lock: outside it, a worker could pass it, be descheduled, and post the card after
            // that dismiss, leaving the ownerless card this guard exists to prevent.
            if (destroyed) return

            // This block runs BEFORE the low one, and it has to: the low block ends in an early
            // `return`, so anything placed after it is skipped on every frame where the low state
            // did not change - which is nearly all of them.
            //
            // Nothing is done at all when the critical answer is null. That is "no profile, so the
            // conversion is unknown", not "not critical": reporting it as not critical would take
            // the card down and re-arm the crossing, so the siren would sound again the moment a
            // profile came back. The state is left exactly as it was and the pump's own card below,
            // which needs no conversion, carries on.
            //
            // On the change of state ONLY, so one crossing rings once. The card is deliberately NOT
            // put back if it leaves the screen, and the reason is the sound: this condition cannot be
            // cleared until the cartridge is changed, so posting it again with the alarm would ring
            // at every attempt to clear it. Posting it again WITHOUT one is not the answer either -
            // an URGENT card with no soundRes still reaches `raiseSystemNotification` when
            // "Raise urgent alarms as Android notifications" is on, which is the default, and that
            // path is audible - but the rule stands whatever that preference says.
            //
            // The only way off the screen here is the user taking it down - on the overview, or by
            // swiping its Android notification - because this version has no mute-all that could
            // delete it behind their back. So the rule above is about a deliberate act, not an
            // accident, and "never put back" costs the user only what they chose to stop seeing.
            //
            // A driver restart does ring once more, deliberately: onDestroy takes the card down, so
            // it has to come back, and coming back at this level without a sound is worth little.
            if (warnings.critical != null && showCritical != lastCriticalReservoir) {
                lastCriticalReservoir = showCritical
                if (showCritical) {
                    aapsLogger.warn(
                        LTag.PUMP,
                        "Reservoir ${cur.reservoirLevel}U on the pump is ${levelIu}IU, " +
                            "at or below the critical level of ${criticalThreshold}IU"
                    )
                    apexNotifications.post(
                        ApexNotifications.RESERVOIR_CRITICAL,
                        rh.gs(R.string.alarm_u_reservoir_critical),
                        soundRes = CoreUiR.raw.alarm,
                    )
                } else {
                    apexNotifications.dismiss(ApexNotifications.RESERVOIR_CRITICAL)
                }
            }

            // On the change of state ONLY, and deliberately without the re-raise that
            // onSystemStateChanged does. A locked keypad or a suspended pump is something the user
            // fixes in seconds, so putting that card back is right; a low cartridge stands until the
            // cartridge is changed, so re-raising it would hand back a card that cannot be cleared for
            // hours - and with "Raise urgent alarms as Android notifications" switched on, which
            // despite its name also mirrors a NORMAL card with no actions, every attempt to clear it
            // would buzz. Told once per crossing is the message, and the live value stays on the
            // overview reservoir row. The pump is no backstop here: this card is posted only when the
            // pump is NOT reporting the condition itself, which is the very case that made it
            // necessary.
            if (show == lastLowReservoir) return
            lastLowReservoir = show

            if (show) {
                aapsLogger.info(
                    LTag.PUMP,
                    "Reservoir ${cur.reservoirLevel}U is at or below the pump's own low level of ${lowReservoirThreshold}U"
                )
                apexNotifications.post(ApexNotifications.RESERVOIR_LOW, rh.gs(R.string.alarm_w_reservoir_low))
            } else {
                apexNotifications.dismiss(ApexNotifications.RESERVOIR_LOW)
            }
        }
    }

    private fun onSystemStateChanged(v1: StatusV1) {
        // Own notification id on purpose. While it shared PUMP_ERROR with onAlarmsChanged the unlocked
        // branch silently cleared the occlusion / empty-reservoir / hardware-fault alarm raised moments
        // earlier by the very same frame, so the user never saw a pump alarm.
        val locked = v1.isLocked == true
        // Act on the change only. This is called once per status frame, so a locked keypad — an ordinary
        // thing to leave switched on — re-posted an URGENT notification about once a minute for as long
        // as it stayed locked. No sound is attached for the same reason: it is a setting, not an alarm.
        // The card is still raised again if it went away while the pump stayed locked, so dismissing it
        // does not hide the state until the next time it changes.
        val cardStanding = apexNotifications.isOnScreen(ApexNotifications.PUMP_LOCKED)
        if (locked == lastIsLocked && (!locked || cardStanding)) return
        lastIsLocked = locked

        if (locked) {
            apexNotifications.post(
                ApexNotifications.PUMP_LOCKED,
                rh.gs(R.string.pump_is_locked),
                level = Notification.URGENT,
            )
        } else {
            apexNotifications.dismiss(ApexNotifications.PUMP_LOCKED)
        }
    }

    private fun onStatusV1(status: StatusV1, epoch: Int) {
        val update = pump.updateFromV1(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V1 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        // The raw temporary-basal fields, on a transition only. See shouldLogTbrTail for why they are
        // not in an ordinary log today and what they answer. Info level: this is diagnostic data that
        // has to survive in an exported log, not per-frame chatter.
        if (ApexDriverLogic.shouldLogTbrTail(update.previous?.tbr, update.current.tbr))
            aapsLogger.info(
                LTag.PUMPCOMM,
                "TBR frame fields: rateRaw=${status.temporaryBasalRate} absoluteRaw=${status.temporaryBasalRateIsAbsoluteRaw} " +
                    "durationMin=${status.temporaryBasalRateDuration} elapsedMin=${status.temporaryBasalRateElapsed} " +
                    "decodedBefore=${update.previous?.tbr} decodedNow=${update.current.tbr}"
            )
        // The pump repeats the same limits in every frame - 452 of them in a 9 hour capture, all
        // reporting 5.0 and 8.0 - and each put() was a SharedPreferences write for a value that had
        // not moved. Compare with what is stored, not with the previous
        // frame: the pump still wins whenever the two differ, so a preference that drifted for any
        // reason is still pulled back, it just stops being rewritten hundreds of times a day.
        // Compared with a tolerance, not for equality: SharedPreferences stores a Double as a Float and
        // reads it back widened, so an exact comparison only ever matches the few values that survive
        // that round trip - about one settable value in five - and the write would still run on every
        // frame for the rest. The tolerance is half a pump step (0.025 U), so it can never hide a real
        // change while still recognising the same value coming back.
        if (abs(preferences.get(ApexDoubleKey.MaxBasal) - update.current.maxBasal) > PUMP_LIMIT_MATCH_U)
            preferences.put(ApexDoubleKey.MaxBasal, update.current.maxBasal)
        if (abs(preferences.get(ApexDoubleKey.MaxBolus) - update.current.maxBolus) > PUMP_LIMIT_MATCH_U)
            preferences.put(ApexDoubleKey.MaxBolus, update.current.maxBolus)
        // The description is derived from maxBasal alone, so it only needs redoing when that moves.
        // A null previous is the first frame this process has seen and must always go through.
        if (update.previous?.maxBasal != update.current.maxBasal) apexPumpPlugin.updatePumpDescription()

        onAlarmsChanged(update)
        onBasalChanged(update)
        onTbrChanged(update, epoch)
        onReservoirChanged(update, status.lowReservoirThreshold)
        onSystemStateChanged(status)

        // Everything this needs is in V1, so it must not wait for a V2 frame that may never come:
        // a missing StatusV2 is no longer an error (see [getStatus]), and a pump that stops
        // answering it would otherwise silently stop having MaxBasal/MaxBolus pushed to it and stop
        // being forced onto basal pattern USED_BASAL_PATTERN_INDEX. Everything it does is guarded by
        // "is the pump still misconfigured", so running it again from onStatusV2 on a healthy 4.11+
        // pump changes nothing.
        onSettingsChanged(update)

        // The battery-swap detector stays on the V2 path for 4.11+ pumps. There a V1 frame either
        // keeps the held exact value - capped when the gauge reports Low or Dead, so it cannot show
        // a rise - or falls back to the coarse gauge, which can raise it but marks itself
        // approximate, and onBatteryChanged refuses that mixed pair anyway (see
        // ApexPump.batteryLevelForV1). Below 4.11 there is no V2 at all and V1 is the only chance to
        // notice.
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == false) onBatteryChanged(update)

        rxBus.send(EventApexPumpDataChanged())
    }

    /**
     * Phone clock of the last [comparePumpInsulinOnBoard] run, so it stays a slow diagnostic.
     *
     * Volatile because V2 frames are processed on frame worker threads, so this is read and written
     * from more than one of them.
     */
    @Volatile private var lastPumpIobCheckAt = 0L

    /**
     * The unrecognised firmware setting already reported, so the urgent card is raised once per value
     * for the life of this service. Never cleared: a new service raises it again, and that repetition
     * is what leads the user out of a setting the driver cannot read.
     */
    @Volatile private var warnedUnknownFirmware: String? = null

    /** Source and reason of the refusal already reported; see [logTbrSyncRefused]. */
    @Volatile private var lastTbrRefusalEpisode: String? = null

    /**
     * Partial wake lock for the driver's own autonomous work, or null if the platform refused one.
     *
     * The driver paces its BLE writes with `SystemClock.sleep`, which counts uptime and does not
     * advance while the CPU is suspended. With the screen off a reconnect measured 32.6 s against
     * 11.3 s in the daytime, the whole difference being three of those sleeps stretching. Commands
     * issued through the core queue are already covered by the queue's own lock; this covers what the
     * driver starts by itself - the connect handshake and a heartbeat cycle.
     *
     * Created here rather than lazily on first use, because first use is the bare thread that runs
     * `onConnect`, and an uncaught throw there kills the process on every connect attempt - the trap
     * this driver has already paid for once. Everything that can throw is caught: a null lock simply
     * makes every call below a no-op.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Guards [wakeLockHolders] and the OS calls on [wakeLock].
     *
     * Its own object, not the service monitor: that one is held by `notifyAboutResponse` on the BLE
     * callback thread, and putting an OS call underneath it is how this driver has stalled that
     * thread before.
     */
    private val wakeLockGuard = Any()

    /** How many episodes currently want the wake lock held. Guarded by [wakeLockGuard]. */
    private var wakeLockHolders = 0

    /**
     * Take the wake lock for one episode of the driver's own work.
     *
     * Counted, so a heartbeat that overlaps a handshake does not release the lock out from under it.
     * The timeout is a backstop only: it must outlast the longest episode by a wide margin, because
     * its whole job is to bound a release that never came.
     */
    private fun acquireWakeLock(reason: String) = synchronized(wakeLockGuard) {
        // A frame queued before onDestroy can still reach this on a pool worker - the executor is shut
        // down politely on purpose - and a destroyed service must not start holding the CPU again.
        if (destroyed) return@synchronized
        wakeLockHolders++
        // Unconditionally, not only on the first holder. acquire(timeout) arms a release the OS
        // performs behind the counter's back, and the counter never learns about it; taking it again
        // on every episode both repairs that state and re-arms the timeout from the episode actually
        // running. setReferenceCounted(false) makes the repeat harmless - one release still drops it.
        try {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            aapsLogger.error(LTag.PUMP, "Could not take the wake lock for $reason", t)
        }
    }

    /** Give back the wake lock for one episode. Safe to call more often than [acquireWakeLock]. */
    private fun releaseWakeLock(reason: String) = synchronized(wakeLockGuard) {
        if (wakeLockHolders == 0) return@synchronized
        wakeLockHolders--
        if (wakeLockHolders > 0) return@synchronized
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
            aapsLogger.error(LTag.PUMP, "Could not give back the wake lock after $reason", t)
        }
    }

    /**
     * Compare the pump's own insulin-on-board figure with the one AAPS computes, and log the pair.
     *
     * `StatusV2.absoluteInsulin` arrives in every V2 frame and, until now, nothing in the driver read
     * it. Over a 9 hour capture it behaved exactly like a bolus IOB curve in 0.025 U steps: decaying
     * between boluses, stepping up at each one, and reaching exactly zero once the night's boluses had
     * washed out - which is why this compares against bolus IOB rather than the total. That reading of
     * the field is an inference from one capture, not something the protocol documentation confirms,
     * so this only writes to the log. Nothing here changes a dose, a database row or a pump command.
     *
     * What it is for: a duplicated or lost bolus shows up as a lasting gap between the two numbers,
     * and today that class of defect is only visible afterwards, by reading rows. Collecting the
     * comparison is the cheap first step; whether it should ever raise something louder is a decision
     * for after there is data.
     */
    private fun comparePumpInsulinOnBoard(status: StatusV2) {
        val now = System.currentTimeMillis()
        // Every two minutes would be pointless for a signal that moves this slowly, and each check
        // costs an IOB calculation on a frame worker thread.
        if (now - lastPumpIobCheckAt < PUMP_IOB_CHECK_INTERVAL_MS) return
        lastPumpIobCheckAt = now

        val pumpIob = status.absoluteInsulin * 0.025
        val aapsIob = runBlocking { iobCobCalculator.calculateIobFromBolus().iob }
        val difference = pumpIob - aapsIob
        val line = "Insulin on board: pump ${"%.3f".format(pumpIob)}U, AAPS ${"%.3f".format(aapsIob)}U, " +
            "difference ${"%.3f".format(difference)}U"
        if (abs(difference) > PUMP_IOB_DIVERGENCE_U) aapsLogger.warn(LTag.PUMP, "$line - larger than expected")
        else aapsLogger.debug(LTag.PUMP, line)
    }

    private fun onStatusV2(status: StatusV2) {
        val update = pump.updateFromV2(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V2 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        // Same as the V1 limits above: the pump repeats this in every V2 frame and it never moves on
        // its own, so only write when the stored value actually differs.
        val alarmLength = status.alarmLength!!.name
        if (preferences.get(ApexStringKey.AlarmSoundLength) != alarmLength)
            preferences.put(ApexStringKey.AlarmSoundLength, alarmLength)

        comparePumpInsulinOnBoard(status)

        onSettingsChanged(update)
        onBatteryChanged(update)

        rxBus.send(EventApexPumpDataChanged())
    }

    @Volatile private var lastBasalProfileRefreshAttempt = 0L

    /**
     * Wrapper so the wake lock is given back on every exit of [onHeartbeatCycle].
     *
     * That body has three of them - the disconnected bail-out and two failed reads - besides the
     * normal end and anything it throws, and a try/finally here covers all of them without touching
     * the body itself.
     */
    private fun onHeartbeat() {
        acquireWakeLock("heartbeat")
        try {
            onHeartbeatCycle()
        } finally {
            releaseWakeLock("heartbeat")
        }
    }

    private fun onHeartbeatCycle() {
        aapsLogger.debug(LTag.PUMPCOMM, "Got heartbeat")
        if (connectionStatus == ApexBluetooth.Status.DISCONNECTED) {
            aapsLogger.error(LTag.PUMPCOMM, "BUG: Got heartbeat but pump is disconnected!")
            return
        }

        status.addAction(ApexDriverStatus.Action.Heartbeat, R.string.action_heartbeat)
        if (!getStatus("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        if (!getBoluses("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        // Re-read the pattern rates at a low cadence: rates edited on the pump keypad are invisible
        // to the cache-based isThisProfileSet, so without this a keypad edit could run unnoticed
        // for the whole connected session.
        val nowMillis = System.currentTimeMillis()
        if ((pump.cachedBasalProfile == null || nowMillis - pump.cachedBasalProfileAt > BASAL_PROFILE_CACHE_MAX_AGE_MS) &&
            nowMillis - lastBasalProfileRefreshAttempt > BASAL_PROFILE_REFRESH_RETRY_MS
        ) {
            lastBasalProfileRefreshAttempt = nowMillis
            getBasalProfiles("HeartbeatHandler-cacheRefresh")
        }
        status.removeAction(ApexDriverStatus.Action.Heartbeat)

        status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
    }

    private fun onVersion(version: Version) {
        aapsLogger.debug(LTag.PUMPCOMM, "Got version - $version")
    }

    private fun onBolusEntry(entry: BolusEntry) {
        // Pure extended-bolus entries carry no duration, so they cannot be reconstructed - skip them.
        // A dual-wave entry (both parts non-zero) still has a usable standard part, and dropping the
        // whole entry hid that insulin from IOB.
        if (entry.extendedDose > 0 && entry.standardDose == 0) return

        // The original driver only looks at recent history: an entry older than 5 h can no longer belong
        // to a bolus this session started, so re-syncing it is pure work. Every poll reads the whole ring
        // buffer, and since these frames are now processed concurrently that work is a per-poll thread
        // fan-out. The inProgressBolus disjunct is kept verbatim - it is what still lets a running bolus
        // be matched when the pump clock has drifted.
        // Remember the newest bolus for the display BEFORE that guard. The guard saves work on entries
        // too old to belong to a bolus this session started, but the pump hands back the same ten-entry
        // ring every two minutes whatever their age, so after a restart with no bolus in the last five
        // hours every entry is dropped and the "Last bolus" row has nothing to show until the next dose.
        // Measured on 2026-08-26: from 08:20 to 10:12 that was 57 reads and 570 entries, all discarded -
        // over two hours of an empty row with the pump holding the answer the whole time.
        //
        // Display state only. It writes no therapy row and calls nothing in core: pumpIdOf() with its
        // cutover stamp, both syncBolusWithTempId paths and the fallback syncBolusWithPumpId all stay
        // below the guard, so the per-poll work the guard was added to prevent is still prevented.
        //
        // Keyed off pump.lastBolus itself rather than a service field, so it survives the service being
        // rebuilt while the singleton pump model lives on. The lock still matters: all ten entries of a
        // read are processed concurrently, so this is a read-modify-write on shared state.
        synchronized(bolusEntryLock) {
            val newest = pump.lastBolus
            if (newest == null || entry.dateTime > newest.dateTime) {
                pump.lastBolus = entry
                rxBus.send(EventApexPumpDataChanged())
            }
        }

        if (pump.inProgressBolus == null && entry.dateTime.millis < System.currentTimeMillis() - HISTORY_RECENCY_WINDOW) return

        // index is printed to settle what it means. It is the only value in a history frame that could
        // separate two boluses of the same minute and the same requested dose - the collision pumpIdOf
        // cannot resolve - but its single reader today treats it as a position in the ring, which
        // shifts as entries age out. The same bolus is already identified on this line by its stamp and
        // dose, so one export shows whether its index stays put across reads or moves. Until that is
        // settled the index stays out of the pump id, and out of any detector built on it.
        aapsLogger.debug(
            LTag.PUMP,
            "Processing bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] " +
                "on ${entry.dateTime} index=${entry.index}"
        )

        // Find the bolus in history and sync it.
        // Pump may round up boluses, use 0.11 for failsafe.
        val entryPumpId = pumpIdOf(entry)

        // bolusEntryLock (not the service monitor - see its doc) guards the whole in-progress-bolus
        // hand-off, so a second BolusEntry frame cannot match the same bolus while this one is being
        // finished: the temp-id sync and its result log, the
        // notifyAll() that releases the bolus() call waiting on inProgressBolus, the 100 ms grace sleep
        // and the clearing of pump.inProgressBolus all run inside. Nothing inside waits for a pump
        // response - the status refresh is only *started* here, on its own thread, and it is the absence
        // of any such wait that keeps this section deadlock-free. Only the fallback syncBolusWithPumpId
        // below runs unlocked: it is keyed by pumpId alone and touches no driver state.
        synchronized(bolusEntryLock) {
            pump.inProgressBolus?.let {
                val delta = abs(entry.dateTime.millis - it.temporaryId)
                // The pump stamps every bolus at second 59 of the minute it ACCEPTED the command, and
                // temporaryId carries the same second-59 grid, so a match normally lands within the
                // milliseconds carried over from the send - hence the first arm.
                //
                // Measured on 2026-08-26 over 9 hours: all 743 history stamps ended in :59.000, and all
                // 14 boluses matched on the first arm with |delta| between 0.035 s and 0.990 s. Seven of
                // them were still delivering when the minute rolled over - one 6.7 U bolus crossed two
                // boundaries, accepted 10:26:35 and finished 10:28:50, and was still stamped 10:26:59.
                // So the stamp follows the START, not the end of delivery, and the second arm never
                // fired. It stays as the safety net for a bolus accepted within a second of a rollover,
                // where the pump's own clock can land in the next minute; that case was not observed
                // and must not be widened without a capture that shows it.
                if (delta <= 1000 || (delta > 59000 && delta < 61000)) {
                    aapsLogger.debug(LTag.PUMP, "Syncing current bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U]")
                    // Identity check: is this history entry the bolus we are tracking? Compare the two
                    // PROGRAMMED amounts — what the pump recorded as asked for, against what we asked
                    // for. It used to compare the programmed amount against `currentDose`, which is
                    // delivery PROGRESS, and that conflates "is this ours" with "did it finish". The two
                    // agree whenever delivery completed, and disagree exactly when it did not: a bolus the
                    // pump stopped part way (occlusion, empty cartridge) failed the test, took the early
                    // return, and never reached syncBolusWithTempId below — leaving the row written at the
                    // start standing at the REQUESTED amount, so AAPS counted insulin that never went in.
                    // The disconnect path already reached the same conclusion and switched to
                    // requestedDose; there is no case where progress is the better key.
                    val deltaU = abs(entry.standardDose * 0.025 - it.requestedDose)
                    if (!it.cancelled && deltaU > 0.11) {
                        aapsLogger.debug(LTag.PUMP, "Not this bolus: requested ${it.requestedDose}U, entry ${entry.standardDose * 0.025}U")
                        return
                    }

                    val syncResult = pumpSync.syncBolusWithTempId(
                        // Keep the time AAPS wrote when it sent the bolus; do NOT overwrite it with
                        // the pump's. The pump keeps history at minute resolution and stamps every
                        // entry at second 59 (that is what the match window above works around), so
                        // its time is up to a minute LATER than the delivery - and later than "now"
                        // when history is read quickly. A bolus dated in the future is left out of
                        // IOB: on 2026-08-09 an SMB of 0.35 U delivered at 11:15:52 was stored as
                        // 11:16:59, the next cycle at 11:16:52 saw IOB 0.526 without it and asked
                        // for another 0.5 U. Only the 5 minute SMB guard stopped the second dose.
                        // 6 of that day's 35 boluses were stored 27-42 s ahead of the write.
                        // Confirmed again on 2026-08-26: 6 of 14 entries were still dated in the
                        // future when they were read, by up to 9.7 s. That much follows from second-59
                        // stamping inside the accepting minute alone - a bolus accepted at :17 is
                        // stored 42 s ahead - so this correction is needed whatever the pump clock does.
                        // The pump time still feeds pumpIdOf() below - that id must stay on the raw
                        // pump grid, or core stops recognising rows it already holds and the same
                        // bolus is inserted twice.
                        timestamp = it.detailedBolusInfo.timestamp,
                        temporaryId = it.temporaryId,
                        amount = entry.standardPerformed * 0.025,
                        pumpId = entryPumpId,
                        pumpType = PumpType.APEX_TRUCARE_III,
                        pumpSerial = apexDeviceInfo.serialNumber,
                        type = it.detailedBolusInfo.bolusType,
                    )
                    aapsLogger.debug(LTag.PUMP, "Final bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] sync succeeded? $syncResult")
                    val confirmedDose = entry.standardPerformed * 0.025
                    synchronized(it) {
                        // The pump's own answer to "how much went in", which is what the waiting
                        // deliverTreatment() call reports and compares against the request. Until this
                        // was written, currentDose held whatever the last live progress frame said -
                        // and on the path that matters, a link that dropped mid-bolus and came back,
                        // there are no more progress frames, so a bolus the pump had finished in full
                        // was read as a half-delivered one. Written under the same monitor as the
                        // notifyAll below, so the waiter cannot see the old value after it wakes.
                        it.currentDose = confirmedDose
                        it.notifyAll()
                    }
                    // The entry above is what the "this bolus may be incomplete" card was waiting for,
                    // so bring the card in line with it. Whether that means taking a card down, putting
                    // one up with the pump's own numbers, or leaving one exactly as it is, is decided in
                    // one place for both threads - see `ApexPumpPlugin.refreshUnconfirmedWarning`.
                    //
                    // The entry does not only settle the doubt in the bolus's favour. The identity check
                    // above compares the PROGRAMMED amounts, so a bolus the pump stopped part way still
                    // matches here, and its entry proves the warning right rather than wrong.
                    apexPumpPlugin.refreshUnconfirmedWarning(it)
                    SystemClock.sleep(100)
                    pump.inProgressBolus = null
                    savePendingBolusSync(null) // matched - nothing left to recover after a restart

                    // Request a fresh status to reflect the delivered bolus. On its own thread: this
                    // runs on the frame worker thread and the read blocks for up to
                    // SINGLE_VALUE_RESPONSE_TIMEOUT - same idiom as onBolusCompleted().
                    // Guarded like processObject(): the send() behind getStatus() dereferences GATT state
                    // that the callback thread can null concurrently, and a revoked BLUETOOTH_CONNECT
                    // surfaces as SecurityException - an uncaught throw on a bare thread kills the process.
                    // force = true: a bolus has just changed the reservoir and the daily total, so the
                    // status we hold is stale by definition. Without it isStatusFresh() drops this read
                    // whenever the last one was under STATUS_FRESH_WINDOW_MS ago, which is most of the time
                    // right after a bolus - the thread is then started and paid for to do nothing.
                    Thread {
                        try {
                            getStatus("ApexService-updateAfterBolus", force = true)
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.PUMPCOMM, "Failed to refresh status after bolus", e)
                        }
                    }.start()
                    return
                }
                if (entry.index < 2) return
            }

            // Same match, but for a bolus whose in-memory state did not survive. Without this the entry falls
            // through to syncBolusWithPumpId below, which looks the row up by pumpId - the row written when the
            // bolus was sent has none, so core inserts a SECOND row and IOB counts the insulin twice, forever.
            // Inside the lock, like the block above, and only when there is no live bolus to match instead.
            if (pump.inProgressBolus == null) {
                val pending = loadPendingBolusSync()
                if (pending != null && pending.matches(entry.dateTime.millis, entry.standardDose, System.currentTimeMillis())) {
                    val syncResult = pumpSync.syncBolusWithTempId(
                        timestamp = pending.timestamp, // the time AAPS wrote, for the reason given above
                        temporaryId = pending.temporaryId,
                        amount = entry.standardPerformed * 0.025,
                        pumpId = entryPumpId,
                        pumpType = PumpType.APEX_TRUCARE_III,
                        pumpSerial = apexDeviceInfo.serialNumber,
                        type = pending.bolusType,
                    )
                    // The proof this bolus's card was waiting for - but only if it IS this bolus's card.
                    // Both records carry the temporary id AAPS gave the bolus, so they can be compared;
                    // without that a later bolus's entry could settle an earlier bolus's card with an
                    // amount that never belonged to it. Read before the line below clears the field.
                    val abandoned = abandonedBolusWithWarning?.takeIf { it.temporaryId == pending.temporaryId }
                    savePendingBolusSync(null)
                    aapsLogger.debug(LTag.PUMP, "Recovered bolus sync after restart [${entry.standardPerformed * 0.025}U] succeeded? $syncResult")
                    abandoned?.let { apexPumpPlugin.settleAbandonedWarning(it, entry.standardPerformed * 0.025) }
                    // A false means core holds no row under that temporaryId (already reconciled, or never
                    // written). Fall through so the entry can still be stored by pumpId - it is then genuinely
                    // an unrecorded bolus, not a duplicate.
                    if (syncResult) return
                } else if (pending != null && pending.isStale(System.currentTimeMillis())) {
                    // Clearing the slot also lets go of any record kept for it - see savePendingBolusSync.
                    savePendingBolusSync(null)
                    aapsLogger.debug(LTag.PUMP, "Dropping stale pending bolus sync (tempId ${pending.temporaryId})")
                }
            }
        }

        // Skip an entry this service has already handed to core at a moment when core could have taken
        // it. The pump returns the same ten-entry ring on every read, so without this the same 20
        // boluses were offered 1144 times in a 9 hour capture - one blocking Room round-trip each, from
        // a raw thread, every one of them returning false. One bolus was offered 109 times.
        //
        // Keyed on the DELIVERED amount as well as the id, not on the id alone. syncBolusWithPumpId
        // returns false both when core already holds this row and when it just corrected that row's
        // amount - SyncPumpBolusTransaction reports a corrected row under `updated`, and only `inserted`
        // is returned - so an id-only key would make the correction one-shot. The pump id is built from
        // the REQUESTED dose and does not move as delivery progresses, so a later read carrying a larger
        // standardPerformed must still get through; otherwise a bolus stored short would stay short for
        // the whole 5 h it matters, which is what the comment further down about amount corrections
        // still going through relies on.
        //
        // Remembering is conditional on a profile, and that is not a guess about core: syncBolusWithPumpId
        // refuses outright when no profile is running at that timestamp, and that refusal has to be
        // retried on the next read or the bolus is lost. Its other refusals are permanent for this entry -
        // core already holds this row unchanged, or the record predates this pump being registered, and a
        // wrong-active-pump refusal cannot recover either: connectNewPump() clears the registration, and
        // the first record offered afterwards re-stamps the change timestamp at "now", so every entry
        // already in the ring is older than it from then on. The one exception is an entry younger than
        // the one-minute grace that registration allows.
        val offerKey = entryPumpId to entry.standardPerformed
        if (offerKey in offeredPumpIds) return

        // A bolus given on the pump keypad has no AAPS-side time, so the pump's stamp is all we have -
        // but it must not be in the future, for the IOB reason described above. Computed once so the
        // profile is looked up for exactly the timestamp the sync uses.
        val bolusTimestamp = historyBolusTimestamp(entry.dateTime.millis, System.currentTimeMillis())

        // Otherwise, just sync the bolus with the DB
        val hadProfile = runBlocking { profileFunction.getProfile(bolusTimestamp) != null }
        val synced = pumpSync.syncBolusWithPumpId(
            timestamp = bolusTimestamp,
            pumpId = entryPumpId,
            amount = entry.standardPerformed * 0.025,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            type = null,
            // The row may already exist under this pumpId from the tempId sync above, and then it
            // carries the time AAPS sent the bolus. The pump only keeps minute resolution and files
            // every entry at second 59, so overwriting would drag a known good time onto that grid.
            // Amount and type corrections still go through.
            updateExistingTimestamp = false,
        )
        // Only now: with no profile the call above was refused and has to come back on the next read.
        if (hadProfile) {
            if (offeredPumpIds.size > SEEN_PUMP_IDS_LIMIT) offeredPumpIds.clear()
            offeredPumpIds.add(offerKey)
        }
        aapsLogger.debug(
            LTag.PUMP,
            if (synced) "Synced bolus ${entry.standardPerformed * 0.025}U on ${entry.dateTime} (pumpId $entryPumpId)"
            // Core refuses a record for several reasons - already stored under this pumpId, older than the
            // moment this pump was registered, or no profile running - so do not claim to know which.
            else "Bolus ${entry.standardPerformed * 0.025}U on ${entry.dateTime} (pumpId $entryPumpId) was not stored by core" +
                if (!hadProfile) " (no profile running - will be offered again)" else ""
        )
    }

    /**
     * Pump id for a history entry, with a one-time cutover.
     *
     * The formula changed (see the companion [pumpIdOf]), and the two forms can never coincide: the
     * pump stamps second 59, so the old id always had `millis % 2000 == 1000` while the new one is a
     * multiple of 2000 plus the dose. Since [PumpSync] deduplicates on pumpId alone, re-reading a
     * bolus that is already in the database under the old id would insert it a *second* time and
     * inflate IOB - on the pump owner's own phone, where history predates this change. Entries older
     * than the moment this build first processed history therefore keep the old id and keep matching
     * their existing rows; everything from that moment on uses the collision-resistant form.
     */
    private fun pumpIdOf(entry: BolusEntry): Long =
        (if (entry.dateTime.millis < pumpIdFormatCutover()) entry.dateTime.millis
        else pumpIdOf(entry.dateTime.millis, entry.standardDose))
            .also { logPumpIdCollision(it, entry) }

    /**
     * What this service has already turned into a pump id: id -> (pump time, requested raw dose).
     *
     * Concurrent because every `BolusEntry` frame of a read is processed on its own worker thread.
     * It holds one entry per distinct bolus this service instance has seen, so it grows with real
     * boluses only - a few tens per day - and is dropped with the service.
     */
    private val seenPumpIds = ConcurrentHashMap<Long, Pair<Long, Int>>()

    /**
     * (pump id, delivered raw dose) pairs this service has already offered to core with a profile
     * running. The delivered amount is part of the key on purpose - see [onBolusEntry] for why an
     * id-only key would make the amount correction one-shot.
     *
     * Concurrent for the same reason as [seenPumpIds]: one worker thread per frame of a read.
     */
    private val offeredPumpIds: MutableSet<Pair<Long, Int>> = ConcurrentHashMap.newKeySet()

    /**
     * Runs [processObject] for every decoded frame, one task per frame.
     *
     * Deliberately a CACHED pool, not a fixed-size one. Some of what `processObject` reaches waits for
     * the pump to answer - `reconcileTbr` can issue a status read, and `onBolusCompleted` starts a
     * history read - and the answer to that wait arrives as another frame, which needs a worker of its
     * own. A bounded pool would let the waiting task hold the last worker while the frame that would
     * release it queues behind, which is the deadlock the per-frame threads avoided by construction.
     * A cached pool keeps that property, since it always adds a worker when none is idle, and only
     * removes what the per-frame threads cost: about 3800 thread creations in a 9 hour capture, now
     * reused instead. Tasks never throw - `processObject` catches everything itself.
     */
    private val frameExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ApexFrame")
    }

    /**
     * Warn when two boluses that differ map to one pump id.
     *
     * In practice this only fires for history older than the format cutover, where the id is the raw
     * pump timestamp and any two entries inside the same second collide whatever their doses. Under the
     * current formula equal ids force the same 2 s slot and the same requested dose, and because the
     * pump stamps every entry at second 59 of its minute a slot only ever holds one stamp value - so
     * the identity compared here is equal too and there is nothing to report. It would fire if the pump
     * ever stamped two different times inside one slot, which is why the check is kept rather than
     * dropped. See [pumpIdOf] for why the same-minute same-dose case has no discriminator at all.
     */
    private fun logPumpIdCollision(pumpId: Long, entry: BolusEntry) {
        if (seenPumpIds.size > SEEN_PUMP_IDS_LIMIT) seenPumpIds.clear()
        val identity = entry.dateTime.millis to entry.standardDose
        val previous = seenPumpIds.putIfAbsent(pumpId, identity)
        if (previous != null && previous != identity)
            aapsLogger.warn(
                LTag.PUMP,
                "Pump id collision: $pumpId already belongs to a bolus at ${previous.first} of raw dose ${previous.second}, " +
                    "now claimed by one at ${entry.dateTime.millis} of raw dose ${entry.standardDose}. " +
                    "Core deduplicates on the id, so one of the two will not reach IOB."
            )
    }

    /**
     * Epoch millis from which [pumpIdOf] uses the current formula; stamped on first use.
     *
     * `@Synchronized` because the body is a read-modify-write on a preference and, since [onBolusEntry]
     * no longer runs under the service monitor, several `BolusEntry` worker threads reach it at once:
     * each would otherwise stamp its own value and the last write would win. Taking the service monitor
     * here cannot recreate the deadlock that removing it from [onBolusEntry] fixed - the body only reads
     * and writes preferences and logs, and never waits for a pump response, so it holds the monitor for
     * a preference access and no longer.
     */
    @Synchronized
    private fun pumpIdFormatCutover(): Long =
        preferences.get(ApexStringKey.PumpIdFormatCutover).toLongOrNull() ?: System.currentTimeMillis().also {
            preferences.put(ApexStringKey.PumpIdFormatCutover, it.toString())
            aapsLogger.info(LTag.PUMP, "Stamped bolus pumpId format cutover at $it; older history keeps the previous id form")
        }

    // !! Unreliable on 6.25 firmware, TODO: think about solution
    private fun onTDDEntry(entry: TDDEntry) {
        // Ignore unreliable TDDs on 6.25 and older FWs
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) return

        pumpSync.createOrUpdateTotalDailyDose(
            timestamp = entry.dateTime.millis,
            pumpId = entry.dateTime.millis,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            bolusAmount = entry.bolus * 0.025,
            basalAmount = entry.basal * 0.025 + entry.temporaryBasal * 0.025,
            totalAmount = entry.total * 0.025,
        )
        aapsLogger.debug(LTag.PUMP, "Synced TDD ${entry.total * 0.025}U on ${entry.dateTime}")
    }

    //////// BLE

    /**
     * Local clean-up when a different pump serial is configured. Writes nothing to the pump.
     *
     * It used to send `SetConnectionProfile` here - value 0x11 with a fixed payload 1,0,2,0,..,8,0.
     * That value is not a "connection profile" at all: it is the pump's eight quick-bolus presets, in
     * 0.025 U steps, so the command silently replaced whatever the user had set on the keypad with
     * 0.025..0.2 U on the first connection to a pump. Confirmed three ways in 2026-09: the maker's own
     * app builds the same 16 bytes from its preset editor, 1179 captured status frames from this
     * user's pump held exactly 1..8 in those slots, and the pump's own preset screen showed
     * 0.02/0.05/0.07/0.10/0.12/0.15/0.17/0.20 afterwards. [StatusV1] already decodes the same eight
     * words as `presetBreakfastA`.. so the read side always knew what they were. Nothing needed the
     * write: the pump is told about a new connection by `RequestHeartbeat` (0x33) from onConnect on
     * protocol 4.10 and newer, and by the local heartbeat loop below that.
     */
    private fun onInitialConnection() {
        preferences.put(ApexDoubleKey.MaxBasal, 0.0)
        preferences.put(ApexDoubleKey.MaxBolus, 0.0)
        pump.cachedBasalProfile = null
        pump.lastStatusReceivedAt = 0
        pumpSync.connectNewPump()
    }

    fun startConnection() {
        // The rule ApexDriverLogic.isSerialConfigured states, applied here as everywhere else: a
        // malformed serial can never match the scan filter, so starting a scan for it would loop
        // through the connect watchdog forever.
        if (!ApexDriverLogic.isSerialConfigured(apexDeviceInfo.serialNumber)) {
            if (apexDeviceInfo.serialNumber.isNotEmpty())
                aapsLogger.warn(LTag.PUMP, "Not connecting - serial number is not 8 digits")
            return
        }
        // The pump refused the data channel for this exact serial+password, and the toggle is still on.
        // Retrying cannot start working and only hammers the pump, so stay quiet until the user edits
        // the password, the serial or the toggle (which lifts the lockout through the observers).
        if (isPasswordLockout()) {
            if (!lockoutConnectLogged) {
                lockoutConnectLogged = true
                aapsLogger.warn(LTag.PUMP, "Not connecting - the pump rejected this connection password. Change it in the Apex settings.")
            }
            return
        }
        // Defensive twin of the clear in onSerialChanged, for the case where the serial did not change
        // through the preference observer at all - e.g. a wholesale settings import followed by a
        // restart. Without it the cached address would keep winning over the configured serial.
        if (apexDeviceInfo.serialNumber != preferences.get(ApexStringKey.LastConnectedSerialNumber) &&
            preferences.get(ApexStringKey.BluetoothAddress).isNotEmpty()
        ) {
            aapsLogger.debug(LTag.PUMP, "Serial number does not match the last connected pump - dropping the cached BT address")
            preferences.put(ApexStringKey.BluetoothAddress, "")
        }
        manualDisconnect = false
        apexBluetooth.connect()
    }

    @Volatile private var lastDisconnect = 0L

    /**
     * @param force bypass the anti-flapping rate limit. Required for the onConnect bail-outs: the
     *   disconnect that started this connection attempt stamped [lastDisconnect] itself, and a failed
     *   handshake completes well inside 15 s, so without this the retry is silently swallowed and the
     *   driver is left connected-but-never-ready with nothing scheduled to try again.
     */
    fun disconnect(isReconnect: Boolean = false, force: Boolean = false) {
        if (!force && SystemClock.uptimeMillis() - lastDisconnect < 15000 && isReconnect) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Last disconnect was not long ago, skipping this one")
            return
        }

        lastDisconnect = SystemClock.uptimeMillis()
        manualDisconnect = !isReconnect
        apexBluetooth.disconnect()
        if (isReconnect)
            apexBluetooth.connect()
        SystemClock.sleep(50)
        if (connectionFinished) connectionFinished = false
    }


    override fun onConnect() {
        try {
            // Inside the try, not before it: the existing finally is what guarantees the release, and
            // taking the lock outside would also skip removeAction on any throw between the two.
            acquireWakeLock("onConnect")
            aapsLogger.debug(LTag.PUMPCOMM, "onConnect")
            status.addAction(ApexDriverStatus.Action.Initializing, R.string.action_initializing)

            connectionId++

            val storedFw = preferences.get(ApexStringKey.FirmwareVer)
            val prefFw = storedFw.toEnumOrNull<FirmwareVersion>() ?: run {
                // A name this build does not know - an app downgrade, a settings import from another
                // fork, a renamed constant. valueOf() threw here, and this runs on a bare thread with
                // no catch above it, so the process died on every connect attempt and kept dying.
                //
                // Falls back in memory only. Writing the repaired value back has now been tried twice
                // and rejected twice, for two different reasons, so it is worth saying why: the write
                // comes back through this key's own observer, which disconnects and reconnects, so it
                // tears down the handshake it runs inside; and guarding that away costs more than it
                // buys, because the stored name is the only record of what the user had. Overwriting
                // it with AUTO cannot help the pump this exists for - firmware 6.24 does not answer
                // the version request, so AUTO fails there too - while it does erase the setting and,
                // by making the stored value readable again, stop the card below from ever returning.
                // Running this branch on every connect costs one enum lookup and one log line.
                aapsLogger.error(LTag.PUMPCOMM, "Unknown firmware version setting '$storedFw' - falling back to automatic detection")
                // Once per unreadable value per service, so the card is raised again after a restart -
                // that repetition, with the text telling the user to pick the version again, is what
                // leads out of a pump AUTO cannot identify.
                if (warnedUnknownFirmware != storedFw) {
                    warnedUnknownFirmware = storedFw
                    apexNotifications.post(
                        Notification.PUMP_ERROR,
                        rh.gs(R.string.notification_unknown_firmware_setting),
                        level = Notification.URGENT,
                    )
                }
                FirmwareVersion.AUTO
            }
            if (prefFw == FirmwareVersion.AUTO) {
                status.addAction(ApexDriverStatus.Action.GettingVersion, R.string.action_getting_version)
                val version = getValue(GetValue.Value.Version)?.firstOrNull()
                status.removeAction(ApexDriverStatus.Action.GettingVersion)

                if (version !is Version) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get version - disconnecting. Serial: ${apexDeviceInfo.serialNumber}")
                    // Connected to *something* that does not answer as a pump: count it so a stale
                    // cached BT address eventually gets dropped and a fresh scan is forced.
                    apexBluetooth.markConnectFailed()
                    return disconnect(true, force = true)
                }

                aapsLogger.debug(LTag.PUMPCOMM, version.toString())
                pump.firmwareVersion = version
            } else {
                pump.firmwareVersion = Version(prefFw.major, prefFw.minor, prefFw.protocolVersion)
                aapsLogger.debug(LTag.PUMPCOMM, "Manual version: ${prefFw.name}")
            }

            val version = pump.firmwareVersion!!
            if (!version.isSupported(FIRST_SUPPORTED_PROTO, LAST_SUPPORTED_PROTO)) {
                aapsLogger.error(LTag.PUMPCOMM, "Unsupported protocol v${version.protocolMajor}.${version.protocolMinor} - disconnecting.")
                apexNotifications.post(
                    Notification.PUMP_ERROR,
                    rh.gs(R.string.notification_pump_unsupported),
                    level = Notification.URGENT,
                )
                return disconnect()
            }

            onVersion(version)

            if (!syncDateTime("BLE-onConnect")) {
                aapsLogger.error(LTag.PUMPCOMM, "Failed to sync date and time - disconnecting.")
                return disconnect(true, force = true)
            }

            if (apexDeviceInfo.serialNumber != preferences.get(ApexStringKey.LastConnectedSerialNumber)) {
                onInitialConnection()
                preferences.put(ApexStringKey.LastConnectedSerialNumber, apexDeviceInfo.serialNumber)
            }

            // Do a fast reconnect on failed commands
            if (!doNotReconnect || pump.inProgressBolus != null) {
                if (!getStatus("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get status - disconnecting.")
                    return disconnect(true, force = true)
                }
                if (!getBoluses("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get boluses - disconnecting.")
                    return disconnect(true, force = true)
                }
                // Cache pattern 7 so isThisProfileSet does not need a live BLE read. A miss is not
                // a connect failure: KeepAlive will then see an empty cache and rewrite the profile.
                if (getBasalProfiles("BLE-onConnect") == null) {
                    aapsLogger.warn(LTag.PUMPCOMM, "Failed to cache basal profiles on connect - profile will be rewritten if needed")
                }
            }

            if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) == true) {
                if (!requestHeartbeat("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to notify about connection - disconnecting.")
                    return disconnect(true, force = true)
                }
            } else {
                spawnHeartbeatLoop()
            }

            unreachableTimerTask?.cancel()
            unreachableTimerTask = null
            // Cancelling the timer does not retract an alert it already raised, leaving a "pump
            // unreachable" card sitting over a pump that is connected and dosing.
            // Both ids are cleared. The driver only ever POSTS its own one now — sharing the core's id
            // let a silent two-minute card replace the core's live audible alarm. Withdrawing the core's
            // card here is still right and still needed: we have just proved the pump is reachable,
            // which is the only thing that alarm asserts, while the core's own re-check runs off
            // KeepAlive and can be minutes away with the siren looping the whole time.
            apexNotifications.dismiss(ApexNotifications.PUMP_LINK_LOST)
            apexNotifications.dismiss(Notification.PUMP_UNREACHABLE)
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
            pump.isInitialized = true
            connectionFinished = true
            doNotReconnect = false
            apexBluetooth.markConnectSuccess()
            aapsLogger.debug(LTag.PUMPCOMM, "Connected to pump ${apexDeviceInfo.serialNumber} (FW ${pump.firmwareVersion})")
            // The password was exchanged in the handshake (before this connect could run); the link is
            // up, so the pump either accepted it or has none. The card is decided here, after the
            // connection has finished, so a failed earlier step never posts it.
            applyPasswordCardsAfterConnect()
            // Last in the connect, and only while nothing is being delivered. The queue is already
            // free to dispatch by this line, so this read can stand in front of a command for as long
            // as it holds the command lock - which is why a pump that says nothing gets four seconds
            // rather than thirty, and why this runs at most once per service run.
            if (pump.inProgressBolus == null) {
                readAlarmHistoryOnce()
                readFillHistoryOnce()
            }
        } finally {
            status.removeAction(ApexDriverStatus.Action.Initializing)
            releaseWakeLock("onConnect")
        }
    }

    @Synchronized
    private fun spawnHeartbeatLoop() {
        Thread {
            val savedConnectionId = connectionId

            while (!destroyed) {
                val now = DateTime.now()
                val msTillNextMinute = now.withSecondOfMinute(5).plus(HEARTBEAT_PERIOD_MINUTES * 60000L).millis - now.millis
                SystemClock.sleep(msTillNextMinute)

                if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Pump has been disconnected. Stopping thread")
                    return@Thread
                }
                if (connectionId != savedConnectionId) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Incorrect connection ID. Stopping thread")
                    return@Thread
                }

                aapsLogger.debug(LTag.PUMPCOMM, "Triggering fake heartbeat")
                onHeartbeat()
            }
        }.start()
    }

    @Volatile private var isDisconnectLoopRunning = false
    /**
     * Wait before retry [attempt]: none, then 5 s, 15 s, 30 s and 30 s from there on.
     *
     * Capped low on purpose. A bolus interrupted by the drop keeps the REQUESTED amount in the database until
     * a history read replaces it with what the pump really gave, so a long wait would leave a wrong IOB
     * standing. The error points at too much IOB, which under-doses, but it should still be short.
     */
    @VisibleForTesting
    fun reconnectBackoffMs(attempt: Int): Long = when {
        attempt <= 0 -> 0L
        attempt == 1 -> 5_000L
        attempt == 2 -> 15_000L
        else         -> 30_000L
    }

    /** Sleeps in short steps so a teardown or a manual disconnect is noticed at once. False = stop waiting. */
    private fun sleepInterruptibly(totalMs: Long): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (manualDisconnect || destroyed || connectionStatus == ApexBluetooth.Status.CONNECTED) return false
            SystemClock.sleep(100)
            slept += 100
        }
        return !manualDisconnect && !destroyed
    }

    /**
     * Retry as soon as the user switches Bluetooth back on.
     *
     * `ApexBluetooth.checkBT` refuses to touch the stack while the radio is off. On its own that
     * would be a trap: the retry loop below ends on a manual disconnect or a service restart, and
     * nothing else looks at the adapter, so the driver could stay asleep long after Bluetooth came
     * back. STATE_OFF is only logged on purpose - calling [disconnect] here would set
     * `manualDisconnect` and stop the retry loop for good, which is exactly the trap.
     *
     * A driver left in CONNECTING is reset here as well, and only from that state. The reset is
     * `ApexBluetooth.disconnect`, never this service's own [disconnect], for the same reason: the
     * service one sets `manualDisconnect`.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON  -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth is on again - retrying the pump connection")
                    if (destroyed || manualDisconnect) return
                    // Off the main thread: both calls below take the Bluetooth connection lock and talk
                    // to the BLE stack, and a broadcast receiver may not block.
                    Thread {
                        // A scan that was running when the radio went off is gone without a callback, so
                        // the driver can still think it is CONNECTING, and everything that could retry
                        // only acts on DISCONNECTED. Clear that here instead of waiting out the connect
                        // watchdog. This is ApexBluetooth.disconnect(), NOT ApexService.disconnect():
                        // it stops the scan and resets the status without setting manualDisconnect, so
                        // the retry loop keeps running.
                        if (connectionStatus == ApexBluetooth.Status.CONNECTING) apexBluetooth.disconnect()
                        if (destroyed || manualDisconnect) return@Thread
                        // Try at once instead of waiting out the 30 s backoff, and make sure a loop
                        // exists at all: spawnLoop only ever starts from onDisconnect, so a radio that
                        // was off since app start never had one.
                        if (connectionStatus == ApexBluetooth.Status.DISCONNECTED) startConnection()
                        spawnLoop()
                    }.start()
                }

                BluetoothAdapter.STATE_OFF -> aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth is off - connect attempts are paused")
            }
        }
    }

    private fun spawnLoop() {
        if (isDisconnectLoopRunning) return
        isDisconnectLoopRunning = true
        Thread {
            var attempt = 0
            while (connectionStatus != ApexBluetooth.Status.CONNECTED && !manualDisconnect && !destroyed && !isPasswordLockout()) {
                if (connectionStatus == ApexBluetooth.Status.DISCONNECTED) {
                    // Back off between attempts. Each attempt already costs 25 s of a high duty-cycle radio
                    // scan, so retrying immediately forever - which is what this did - keeps the radio at
                    // roughly full power for as long as the pump is away, and the pump is away for hours when
                    // it is simply out of range. The first retry stays immediate so a brief hiccup still
                    // recovers at once. This does NOT delay dosing: a command connects on its own through
                    // ApexPumpPlugin.connect(), which acts whenever the status is DISCONNECTED - exactly the
                    // state we are in while waiting here.
                    if (!sleepInterruptibly(reconnectBackoffMs(attempt))) break
                    if (connectionStatus != ApexBluetooth.Status.DISCONNECTED) continue
                    aapsLogger.debug(LTag.PUMPCOMM, "Starting connection loop (attempt ${attempt + 1})")
                    if (!doNotReconnect) startConnection()
                    attempt++
                }
                // No reset here. Between attempts the status sits at CONNECTING for the whole 25 s the platform
                // is given, and zeroing the counter there would make every wait the first one again - the
                // backoff would never grow. The counter is local and the loop ends as soon as the pump is
                // CONNECTED, so the next outage starts from zero by construction.
                SystemClock.sleep(100)
            }

            if (manualDisconnect) {
                aapsLogger.debug(LTag.PUMPCOMM, "Manual disconnect detected!")
                disconnect()
            }

            aapsLogger.debug(LTag.PUMPCOMM, "Exiting")
            isDisconnectLoopRunning = false
        }.start()
    }

    override fun onDisconnect() {
        aapsLogger.debug(LTag.PUMPCOMM, "onDisconnect")
        pump.inProgressBolus?.lockHistory = false
        connectionFinished = false

        isGetThreadRunning = false
        // Wake the gate worker if it is parked on the reply, and drop the verdict so a later probe that
        // skips FFC1 cannot reuse last session's answer. The lockout and reject latch are NOT cleared
        // here - that is the anti-hammer; only a settings change lifts them.
        synchronized(passwordReplyLock) {
            passwordReply = null
            passwordReplyLock.notifyAll()
        }
        synchronized(getValueResult) {
            getValueResult.waiting = false
            getValueResult.notifyAll()
        }
        synchronized(commandResponse) {
            commandResponse.waiting = false
            commandResponse.notifyAll()
        }


        lastConnectedTimestamp = System.currentTimeMillis()

        if (unreachableTimerTask == null)
            unreachableTimerTask = timer.schedule(120000) {
                // Own id, not the core's PUMP_UNREACHABLE. That one is posted and dismissed by
                // LocalAlertUtilsImpl at the user's own threshold; sharing it meant this early, silent
                // card could replace the core's live audible alarm, and the core could clear this card
                // while the pump was still not delivering.
                apexNotifications.post(
                    ApexNotifications.PUMP_LINK_LOST,
                    rh.gs(R.string.error_pump_unreachable),
                )
                aapsLogger.error(LTag.PUMP, "Pump unreachable!")
            }

        if (!manualDisconnect) spawnLoop()
    }

    private fun createSpecialModeAlarm() {
        uiInteraction.runAlarm(
            rh.gs(R.string.bolus_error_pump_special_mode),
            rh.gs(R.string.pump_special_mode),
            AlarmSound.BOLUS_ERROR
        )
    }

    /**
     * Print one alarm-history record, decoded, with its raw bytes beside it.
     *
     * The layout is fixed by the first full history, captured on 2026-08-31: 66 records covering
     * 2026-05-05 to 2026-08-31. Byte 1 is the index, bytes 2..7 the date and time as
     * [app.aaps.pump.apex.utils.getDateTime] reads them, bytes 8..9 the code, little endian.
     *
     * `+ 0x100` is the applied reading, matching `AlarmObject.rawType`, and the maker's own history
     * screen resolves the stored word the same way - `alarmArr[raw - 1]`, an array of exactly the
     * fifteen first-family alarms, with no reading for a second-family record at all. `or 0x100` is
     * the only other way to read the word, and it differs from `+ 0x100` only when the stored word
     * already carries bit 8, which every code in all 66 captured records did not (they were 1, 3, 5
     * or 8). It is printed only when it disagrees, which marks exactly the record that would settle
     * what the pump means by such a word.
     *
     * The raw bytes stay in the line. They are the part that survives a decoder being wrong.
     */
    private fun logAlarmHistoryEntry(command: PumpCommand) {
        val data = command.objectData
        if (data.size < 10) {
            aapsLogger.info(LTag.PUMPCOMM, "ALARM-HISTORY: short record, ${data.size} bytes - ${data.toHex()}")
            return
        }
        val index = data[1].toUByte().toInt()
        val raw = getUnsignedShort(data, 8)
        val alarm = PumpAlarm.of(raw + 0x100)

        // An unused ring slot carries the 'no error' code. One short line rather than a decoded record,
        // so a pump with little history does not bury the real entries.
        // Raw bytes here too, short as the line is: a mis-framed record whose code word happens to
        // read as zero would otherwise be filed as an empty slot with nothing left to check it against.
        if (alarm.type == Alarm.NoError) {
            aapsLogger.info(LTag.PUMPCOMM, "ALARM-HISTORY: [$index] empty slot bytes=${data.toHex()}")
            return
        }

        // The date is read before validate() has run, so it can be nonsense on a mis-framed record.
        // Checked rather than trusted: a wrong date printed as a fact is worse than no date.
        //
        // And caught as well as checked. validateDateTime bounds the day at 1..31 without looking at
        // the month, so the 30th of February passes it and then throws out of the date constructor.
        // This runs on the BLE callback thread with nothing above it to catch anything: a diagnostic
        // must not be able to take the link down over a corrupt history record.
        val date = try {
            if (validateDateTime(data, 2, apexDeviceInfo)) getDateTime(data, 2, apexDeviceInfo).toString()
            else "no valid date"
        } catch (e: Exception) {
            "unreadable date (${e.javaClass.simpleName})"
        }

        // Second reading of the same bytes, printed ONLY when it disagrees. The stored word is a bare
        // code, so `+ 0x100` and `or 0x100` name the same alarm for everything seen so far - all 66
        // records of the 2026-08-31 capture. They can only diverge on a second-family code, which no
        // capture holds yet, and that is exactly the record worth looking at twice.
        val alternative = Alarm.entries.find { it.raw == (raw or 0x100) }
        val ambiguity = if (alternative != null && alternative != alarm.type) " (or ${alternative.name})" else ""
        val screenCode = alarm.screenCode?.let { ", $it" } ?: ""

        aapsLogger.info(
            LTag.PUMPCOMM,
            "ALARM-HISTORY: [$index] $date - ${alarm.type.name}$ambiguity (${alarm.code}$screenCode) bytes=${data.toHex()}"
        )
    }

    /**
     * One notification from the pump's password characteristic, on a binder thread.
     *
     * Does nothing but decode and hand over: the decision lives in [checkPumpPasswordOnce], which is
     * waiting on [passwordReplyLock]. Never logs the byte's meaning as a fact about the password -
     * that is the caller's job, once, with the context of what was sent.
     */
    override fun onPasswordReply(value: ByteArray) {
        val reply = ApexDriverLogic.passwordReply(value)
        aapsLogger.debug(LTag.PUMPCOMM, "PASSWORD: reply ${reply.name}")
        synchronized(passwordReplyLock) {
            passwordReply = reply
            passwordReplyLock.notifyAll()
        }
    }

    @Volatile private var isGetThreadRunning = false
    override fun onPumpCommand(command: PumpCommand) {
        if (command.id == null) {
            aapsLogger.error(LTag.PUMPCOMM, "Invalid command with crc ${command.checksum}")
            return
        }

        val type = PumpObject.findObject(command.id!!, command.objectData, aapsLogger)
        aapsLogger.debug(LTag.PUMPCOMM, "from PUMP: ${command.id!!.name}, ${type?.name}")

        if (type == null) {
            // Not just noise. The maker's app decodes value id 0xFF as "no such record", so it is a
            // real answer this pump can give - and the most likely one to a value never asked before.
            // Logged rather than silently dropped, because a read waiting on it is released only by
            // the early-give-up timer and would otherwise be reported as "the pump said nothing".
            aapsLogger.info(LTag.PUMPCOMM, "Unmapped answer from the pump: ${command.objectData.toHex()}")
            return
        }
        if (type == PumpObject.CommandResponse) return onCommandResponse(CommandResponse(command))

        val obj = when (type) {
            PumpObject.Heartbeat       -> Heartbeat()
            PumpObject.StatusV1        -> StatusV1(command, apexDeviceInfo)
            PumpObject.StatusV2        -> StatusV2(command)
            PumpObject.BasalProfile    -> BasalProfile(command)
            PumpObject.AlarmEntry      -> AlarmObject(command, apexDeviceInfo)
            PumpObject.TDDEntry        -> TDDEntry(command, apexDeviceInfo)
            PumpObject.BolusEntry      -> BolusEntry(command, apexDeviceInfo)
            PumpObject.FirmwareEntry   -> Version(command)
            PumpObject.FillEntry       -> FillEntry(command)
            else                       -> return
        }
        // The whole product of the fill-history read. Logged here, before any of the paths below can
        // drop the frame without leaving a trace.
        if (type == PumpObject.FillEntry)
            aapsLogger.info(LTag.PUMPCOMM, "FILL-HISTORY: raw=${command.objectData.toHex()}")
        // Diagnostic only, and deliberately before anything can drop the frame: the empty-history
        // sentinel just below, the empty-slot skip and the date check all return without leaving a
        // decoded trace. One full history has been captured since (see [readAlarmHistoryOnce]); this
        // stays because the second alarm family still has no captured record.
        if (type == PumpObject.AlarmEntry) logAlarmHistoryEntry(command)
        // A pump with an empty history ring buffer answers a history read with a single FF-FF sentinel
        // instead of an entry. It MUST be short-circuited here, before validate(): the sentinel has no
        // valid date, so validate() rejects it and returns without ever calling notifyAboutResponse(),
        // the reader waits out the full 30 s timeout, getBoluses() fails and onConnect() bails — an
        // endless connect/disconnect loop on exactly the state a fresh pump is in. First seen with the
        // bolus history; the pump sends the same sentinel for the other history reads, and only the
        // history objects are covered because StatusV1/V2/Version carry real fields in bytes 2-3.
        val isHistoryEntry = type == PumpObject.BolusEntry || type == PumpObject.TDDEntry ||
            type == PumpObject.AlarmEntry || type == PumpObject.FillEntry
        if (isHistoryEntry && command.objectData.size >= 4 &&
            (command.objectData[2].toInt() and 0xFF) == 0xFF && (command.objectData[3].toInt() and 0xFF) == 0xFF
        ) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got empty history sentinel ($type) - completing the read")
            notifyAboutResponse(command, obj, type)
            return
        }

        val validationMsg = try {
            obj.validate()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPCOMM, "Failed to validate object $type - ${command.objectData.toHex()}", e)
            // The frame IS the answer to the read that is waiting, even though we cannot use it.
            notifyAboutSkippedObject(type, emptyResult = false)
            return
        }
        if (validationMsg != null) {
            // An unused ring-buffer slot is normal on a pump with little history — not an error, and
            // it must not raise an alarming notification.
            if (validationMsg == PumpObjectModel.EMPTY_SLOT) {
                aapsLogger.debug(LTag.PUMPCOMM, "Skipping empty ring buffer slot ($type)")
                // A history of nothing but empty slots is an answered read with no entries, not a
                // read that never got an answer.
                notifyAboutSkippedObject(type, emptyResult = true)
                return
            }
            aapsLogger.error(LTag.PUMPCOMM, "Got invalid (reason: $validationMsg) object of type $type - ${command.objectData.toHex()}")
            // Never for a log-only diagnostic read. Those are switched on for their raw bytes, their
            // layouts are unverified by definition, and the frame is already in the log above - worth
            // more than an urgent card the user cannot act on. Stated once, as a set, so the next
            // unverified read cannot forget it.
            if (config.isEngineeringMode() && type !in LOG_ONLY_DIAGNOSTIC_OBJECTS) {
                apexNotifications.post(
                    ApexNotifications.INVALID_OBJECT,
                    "Invalid object data ($type, $validationMsg) - please send log to developers!",
                    level = Notification.URGENT,
                )
            }
            notifyAboutSkippedObject(type, emptyResult = false)
            return
        }

        val epoch = tbrSyncEpoch
        notifyAboutResponse(command, obj, type)
        // execute() throws once the pool is shut down, where Thread.start() never did. onDestroy shuts
        // it down while a GATT thread can already be inside onPumpCommand - clearCallback only stops the
        // NEXT frame - and nothing between here and the Android BluetoothGattCallback catches anything,
        // so an uncaught throw here would kill the process on a plugin switch.
        try {
            frameExecutor.execute { processObject(obj, epoch) }
        } catch (e: RejectedExecutionException) {
            aapsLogger.debug(LTag.PUMPCOMM, "Service is shutting down - dropping frame of type $type")
        }
    }

    private fun processObject(obj: PumpObjectModel, epoch: Int) {
        try {
            when (obj) {
                is StatusV1 -> onStatusV1(obj, epoch)
                is StatusV2 -> onStatusV2(obj)
                is Heartbeat -> onHeartbeat()
                is BolusEntry -> onBolusEntry(obj)
                is TDDEntry -> onTDDEntry(obj)
                else -> {}
            }
        } catch (e: Exception) {
            // Runs on a raw worker thread — a malformed frame must not crash it with an uncaught exception.
            aapsLogger.error(LTag.PUMPCOMM, "Failed to process pump object ${obj::class.simpleName}", e)
        }
    }

    @Synchronized
    private fun notifyAboutResponse(command: PumpCommand, obj: PumpObjectModel, type: PumpObject) {
        if (!getValueResult.waiting) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got pump command but not waiting for it")
            return
        }
        if (type != getValueResult.targetObject) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got incorrect object type (${type.name} vs ${getValueResult.targetObject?.name})")
            return
        }

        // Pump may send fake bolus entry if there are no boluses in history.
        // We shouldn't handle it.
        // `Byte.toUInt()` sign-extends first, so 0xFF became -1 and this branch could never be taken;
        // the sentinel was then added to the result list as if it were a real bolus entry. Mask instead.
        if (command.objectData.size >= 4 &&
            (command.objectData[2].toInt() and 0xFF) == 0xFF && (command.objectData[3].toInt() and 0xFF) == 0xFF
        ) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got fake bolus entry - skipping")
            getValueResult.waiting = false
            getValueResult.response = arrayListOf()
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
            return
        }

        getValueResult.add(obj)
        if (getValueResult.isSingleObject) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got single value - everything is ready")
            getValueResult.waiting = false
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
        } else {
            getValueResult.framesSeen++
            if (getValueResult.expectsRecordCount && getValueResult.declaredRecordCount == null)
                getValueResult.declaredRecordCount = command.declaredRecordCount
            aapsLogger.debug(LTag.PUMPCOMM, "Updating last timestamp")
            getValueLastTaskTimestamp = System.currentTimeMillis()
            runGetThread()
        }
    }

    /**
     * Tell a waiting read that its answer arrived but could not be used.
     *
     * [notifyAboutResponse] is only reached by an object that passed validation, so a frame dropped
     * for a bad field used to leave the reader waiting for an answer that will never come again:
     * 10 s for a single value, 30 s for a history read, and on the connect path a failed read tears
     * the link down and starts the reconnect loop.
     *
     * A chunked read is NOT finished here - the frame only refreshes the "still arriving" timer, the
     * same way a good frame does, so the entries around a bad one are still collected. A single value
     * read has nothing left to wait for, so it is released at once with no response, which the caller
     * already handles as a failed read.
     *
     * @param emptyResult mark the read as answered with nothing. Used for an unused ring buffer slot:
     *   a history read that is only empty slots is a pump with no history, not a failed read, and
     *   leaving the response null would make getBoluses() fail and onConnect() drop the link.
     */
    @Synchronized
    private fun notifyAboutSkippedObject(type: PumpObject, emptyResult: Boolean) {
        if (!getValueResult.waiting) return
        if (type != getValueResult.targetObject) return

        if (emptyResult && getValueResult.response == null) getValueResult.response = arrayListOf()
        // Only a frame we had to throw away makes the later "no response" log misleading; an empty
        // slot is a normal answer. This write must stay here - ahead of runGetThread() and inside the
        // waiting/targetObject guards above - because a reader now depends on it: see droppedFrame.
        if (!emptyResult) getValueResult.droppedFrame = true

        if (getValueResult.isSingleObject) {
            aapsLogger.debug(LTag.PUMPCOMM, "Single value cannot be used - completing the read")
            getValueResult.waiting = false
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
        } else {
            // Counted like any other frame: the pump sent it, so a comparison against what the pump
            // said it was sending has to include it. Only [InGetValueResult.response] is about what
            // survived.
            getValueResult.framesSeen++
            aapsLogger.debug(LTag.PUMPCOMM, "Skipped object - keeping the chunked read alive")
            getValueLastTaskTimestamp = System.currentTimeMillis()
            runGetThread()
        }
    }

    @Synchronized
    private fun runGetThread() {
        if (isGetThreadRunning) return
        isGetThreadRunning = true
        aapsLogger.debug(LTag.PUMPCOMM, "Running GET thread")
        Thread {
            while (isGetThreadRunning && !destroyed) {
                val quietFor = System.currentTimeMillis() - getValueLastTaskTimestamp
                if (quietFor >= CHUNKED_READ_IDLE_MS) break
                // Sleep to the deadline instead of polling every 100 ms. Each frame pushes
                // getValueLastTaskTimestamp forward, so waking earlier than that can only ever find the
                // read still running. The poll produced about 2300 "Response is not ready yet" lines in
                // a 9 hour capture - 14 % of all PUMPCOMM output - and knew nothing the deadline does
                // not. The completion rule itself is unchanged: still 500 ms of silence.
                SystemClock.sleep(CHUNKED_READ_IDLE_MS - quietFor)
            }
            if (!isGetThreadRunning) {
                aapsLogger.debug(LTag.PUMPCOMM, "GET thread killed")
                return@Thread
            }
            // The loop also exits on `destroyed`, and onDestroy does NOT clear isGetThreadRunning the
            // way every other abort does - so without this the read would look completed here. It was
            // not: the service is going away mid-series, and the summary below would report the app's
            // own teardown as a pump that stopped sending.
            //
            // Everything except that summary still has to happen. The waiting reader is released here
            // exactly as the normal path releases it, because nothing else will do it any more:
            // onDestroy clears the BLE callback before it disconnects, and every other releaser of
            // this waiter - `onDisconnect`, and a frame reaching `notifyAboutResponse` - arrives
            // through that same callback. A reader left asleep would hold the command lock for the
            // rest of its 30 s and then log a timeout that did not happen.
            if (destroyed) {
                aapsLogger.debug(LTag.PUMPCOMM, "GET thread stopped - service destroyed mid-read")
                isGetThreadRunning = false
                getValueResult.waiting = false
                synchronized(getValueResult) {
                    getValueResult.notifyAll()
                }
                return@Thread
            }
            isGetThreadRunning = false

            // Measurement, not a decision. A read that ends on silence cannot tell a complete series
            // from one cut short by a pump that paused longer than the idle window, so the numbers are
            // put side by side in the log. If they turn out to always agree, the count can end the read
            // instead of the timer; if they ever disagree, this line is the evidence for it.
            //
            // Three numbers, because two would mislead: `kept` excludes empty slots and frames dropped
            // as undecodable, while the pump counted everything it sent. `seen` is what to compare.
            val declared = getValueResult.declaredRecordCount
            val seen = getValueResult.framesSeen
            val kept = getValueResult.response?.size ?: 0
            if (declared != null && declared != seen)
                aapsLogger.info(LTag.PUMPCOMM, "RECORD-COUNT: pump declared $declared records, saw $seen frames, kept $kept")
            else
                aapsLogger.debug(LTag.PUMPCOMM, "RECORD-COUNT: declared $declared, seen $seen, kept $kept")

            aapsLogger.debug(LTag.PUMPCOMM, "Chunked response has completed")
            getValueResult.waiting = false
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
        }.start()
        // No sleep here to "let the thread start". isGetThreadRunning is set above, before start(), so
        // the next caller already sees it and returns; nothing downstream depends on the thread having
        // run. The sleep only blocked the caller - and the caller is notifyAboutResponse, which is
        // @Synchronized, so it held the service monitor and the BLE callback thread with it for 50 ms
        // on every chunked read.
    }

    //////// Binder

    private val binder = LocalBinder(this)
    override fun onBind(intent: Intent?): IBinder {
        aapsLogger.debug(LTag.PUMP, "Binding service")
        return binder
    }

    // Static (non-inner) Binder holding a WeakReference to the service. A Binder returned from onBind()
    // is kept alive by a JNI global ref held by the system; a non-static inner Binder would then retain
    // the whole Service via this$0 after onDestroy (LeakCanary: JNI_GLOBAL → ApexService$LocalBinder.this$0).
    class LocalBinder(service: ApexService) : Binder() {
        private val serviceRef = WeakReference(service)
        val serviceInstance: ApexService? get() = serviceRef.get()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        aapsLogger.debug(LTag.PUMP, "Service started")
        return START_STICKY
    }
}