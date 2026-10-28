package app.aaps.pump.apex

import androidx.annotation.VisibleForTesting
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.connectivity.commands.pump.BatteryLevel as PumpBatteryGauge
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.apex.utils.toEnumOrNull
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import org.joda.time.DateTime
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.roundToInt

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
@SingleIn(AppScope::class)
@Inject
class ApexPump(
    val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
) {
    private var _status: PumpStatus? = null
    val status: PumpStatus?
        get() = _status

    val batteryLevel: BatteryLevel
        get() = status?.batteryLevel ?: BatteryLevel(0, 0.0, true)

    /**
     * Battery percentage to publish to AAPS-core, or `null` while no status frame has arrived.
     *
     * Not [batteryLevel], which answers "no status yet" with 0 %. That fallback is right inside this
     * class - the change detection in [updateFromV1] and [updateFromV2] needs something to compare
     * against on the first frame - but publishing it means core cannot tell "we have not heard from
     * the pump" from "the pump says the cell is Dead". Every other driver publishes `null` there.
     *
     * The difference is not cosmetic: `TriggerPumpBatteryLevel` reads the published value directly,
     * so a "battery below N" automation saw 0 % on every app start while this published 0.
     */
    val batteryPercentageOrNull: Int?
        get() = status?.batteryLevel?.percentage

    val isAdvancedBolusEnabled: Boolean
        get() = status?.isAdvancedBolusEnabled ?: false

    val currentBasalPattern: Int
        get() = status?.currentBasalPattern ?: 0

    val maxBasal: Double
        get() = status?.maxBasal ?: 0.0

    val maxBolus: Double
        get() = status?.maxBolus ?: 0.0

    val dateTime: DateTime
        get() = status?.dateTime ?: DateTime(0)

    val reservoirLevel: Double
        get() = status?.reservoirLevel ?: 0.0

    /**
     * Insulin the pump reports delivered so far today, in U. Only StatusV1 carries it, so the V2
     * update reads it back through here to carry the last known figure forward.
     */
    val todayDeliveredUnits: Double
        get() = status?.todayDeliveredUnits ?: 0.0

    val alarms: List<PumpAlarm>
        get() = status?.alarms ?: listOf()

    val tbr: TBR?
        get() = status?.tbr

    val basal: Basal?
        get() = status?.basal

    /**
     * True while the pump is standing on an alarm that stops the motor, kept across the single clean
     * frames the pump emits in between - see [ApexDriverLogic.DELIVERY_STOP_CLEAR_FRAMES].
     *
     * Written only from [updateFromV1], read from the plugin on other threads, hence volatile.
     */
    @Volatile private var deliveryStoppedByAlarm: Boolean = false

    /** Consecutive frames seen with no delivery-stop alarm while [deliveryStoppedByAlarm] stands. */
    @VisibleForTesting
    @Volatile internal var cleanFramesSinceDeliveryStop: Int = 0

    /**
     * Guards the two fields above. Volatile is not enough: counting clean frames is a read, a
     * decision and a write, and `ApexService` hands every status frame to its own thread out of a
     * cached pool, so two frames can be inside [updateDeliveryStopLatch] at once. Both hazards below
     * are two threads INSIDE this function, which is exactly what a lock removes:
     *  - two clean frames both read the same count and both write count+1, so one is lost. Harmless
     *    by itself, it only lengthens the wait.
     *  - a thread that stalls between the read and the write adds its increment on top of a count an
     *    "alarm is back" frame reset underneath it, and can then clear the latch on the very frame
     *    that re-reported the alarm. That is the one that matters.
     *
     * What this lock does NOT give is any ordering between frames - it serialises the updates, it
     * does not sort them. Whole frames can still be handled out of the order the pump sent them, as
     * they always could: `updateFromV1` writes `_status` and its neighbours with no ordering check
     * either. That is older ground and this lock does not claim to fix it. Its residual here is one
     * banked clean frame, and the next frame carrying the alarm resets the count anyway.
     */
    private val deliveryStopLock = Any()

    /**
     * Whether a motor-stopping alarm is standing, debounced.
     *
     * Read by `ApexService.temporaryBasal`, which refuses to send or book a rate while it is true,
     * and by the tests. Deliberately the debounced value and not the raw alarm slots: the slots go
     * empty for a frame at a time while the motor is still stopped, and a guard reading them
     * directly would open for exactly that frame.
     */
    val isDeliveryStoppedByAlarm: Boolean
        get() = deliveryStoppedByAlarm

    /**
     * The pump itself reports no running basal rate - paused on the keypad, or refilling.
     *
     * The narrow half of [isSuspended], kept separate because one caller needs exactly this and not
     * the alarm half: `ApexPumpPlugin.cancelTempBasal` must stay open while the motor is stopped, so
     * that a temporary basal recorded before the stop can still be taken off the books.
     */
    val isBasalPaused: Boolean
        get() = status != null && basal == null

    /**
     * Suspended = the pump is not putting insulin in, either because it reports no running basal
     * rate or because it is standing on an alarm that stopped the motor.
     *
     * Requires a status to have been received: without the `status != null` guard "we have not asked
     * the pump yet" was indistinguishable from "the pump is paused", so the driver reported a
     * suspended pump before its first status frame.
     *
     * The alarm half is not cosmetic. A pump stopped on `ReservoirEmpty` keeps reporting a running basal
     * rate, so this answered false while nothing was being delivered, and the check in
     * `LoopPlugin.applyTBRRequest` - which exists precisely to keep the loop off a suspended pump -
     * never fired. Measured on 2026-08-31: the pump stopped at 20:46:38 and for the next 26 minutes
     * its own delivered-dose counter did not move, yet AAPS sent and booked five temporary basals,
     * up to 1.875 U/h, each reported back as enacted. Boluses were refused by the pump itself in the
     * same window, so only the temporary basal path was writing insulin that never existed.
     */
    val isSuspended: Boolean
        get() = status != null && (basal == null || deliveryStoppedByAlarm)

    val isTBRunning: Boolean
        get() = tbr != null

    val isBolusing: Boolean
        get() = inProgressBolus != null

    val settingsAreUnadvised: Boolean
        get() = isAdvancedBolusEnabled || currentBasalPattern != ApexService.USED_BASAL_PATTERN_INDEX

    var lastV1: StatusV1? = null
    var lastV2: StatusV2? = null

    /**
     * When [lastV2] was decoded, in wall-clock milliseconds; 0 means "never".
     *
     * Only [batteryLevelForV1] reads it. Visible for tests so the stale branch can be reached
     * without waiting for real time to pass.
     */
    @VisibleForTesting
    var lastV2At: Long = 0

    var inProgressBolus: InProgressBolus? = null
    var lastBolus: BolusEntry? = null
    var firmwareVersion: Version? = null
    var serialNumber: String = ""

    /**
     * True only after a successful handshake in this service lifetime.
     * Default false: "we have not talked to the pump yet" must not look like a ready pump.
     */
    @Volatile var isInitialized: Boolean = false

    /**
     * Phone-clock millis when the last StatusV1 frame was applied. 0 means never.
     * Used to skip a redundant status read; must not be the pump RTC (see [ApexDriverLogic.isStatusFresh]).
     */
    @Volatile var lastStatusReceivedAt: Long = 0

    /**
     * Last 48 basal rates read from pattern [ApexService.USED_BASAL_PATTERN_INDEX], or written by us.
     * [ApexPumpPlugin.isThisProfileSet] compares against this cache and must not talk to the pump.
     * Setting it also stamps [cachedBasalProfileAt], which the heartbeat uses to re-read the rates
     * at a low cadence (keypad edits of the rates are invisible in status frames).
     */
    @Volatile var cachedBasalProfile: List<Double>? = null
        set(value) {
            field = value
            cachedBasalProfileAt = if (value != null) System.currentTimeMillis() else 0L
        }

    /** Phone-clock millis when [cachedBasalProfile] was last filled; 0 while it is empty. */
    @Volatile var cachedBasalProfileAt: Long = 0L

    fun updateFromV1(obj: StatusV1): StatusUpdate {
        val updates = arrayListOf<Update>()
        val decodedTbr = ApexDriverLogic.decodeStatusTbr(
            activeRaw = obj.temporaryBasalActiveRaw,
            isAbsoluteRaw = obj.temporaryBasalRateIsAbsoluteRaw,
            rateRaw = obj.temporaryBasalRate,
            durationMinutes = obj.temporaryBasalRateDuration,
            elapsedMinutes = obj.temporaryBasalRateElapsed,
        )
        val new = PumpStatus(
            batteryLevel = batteryLevelForV1(obj),
            isAdvancedBolusEnabled = obj.advancedBolusEnabled,
            currentBasalPattern = obj.currentBasalPattern,
            maxBasal = obj.maxBasal * 0.025,
            maxBolus = obj.maxBolus * 0.025,
            tbr = ApexDriverLogic.resolvedTbr(decodedTbr, status?.tbr),
            basal = if (obj.currentBasalRate == UShort.MAX_VALUE.toInt())
                null
            else Basal(obj.currentBasalRate * 0.025),
            dateTime = obj.dateTime,
            reservoirLevel = obj.reservoir / 1000.0,
            alarms = obj.alarms,
            todayDeliveredUnits = obj.totalDailyDose * 0.025,
        )

        // Independent `if`s, deliberately NOT a subjectless `when`: a subjectless `when` is a
        // first-match if/else chain, so the list could only ever hold ONE entry and every later
        // branch was unreachable. A real session logged a frame that changed both the battery object
        // and the reservoir (136.500 U -> 136.225 U), yet only BatteryChanged was recorded.
        // `changes` is a debug-log payload only — the ApexService handlers diff `previous` against
        // `current` themselves and never branch on this list — so listing every changed aspect is a
        // logging fix, not a behaviour change.
        if (new.basal != basal) updates.add(Update.BasalChanged)
        if (new.tbr != tbr) updates.add(Update.TBRChanged)
        if (new.maxBasal != maxBasal || new.maxBolus != maxBolus) updates.add(Update.ConstraintsChanged)
        if (new.currentBasalPattern != currentBasalPattern || new.isAdvancedBolusEnabled != isAdvancedBolusEnabled) updates.add(Update.UnadvisedSettingsChanged)
        if (new.batteryLevel != batteryLevel) updates.add(Update.BatteryChanged)
        if (new.reservoirLevel != reservoirLevel) updates.add(Update.ReservoirChanged)
        if (new.alarms != alarms) updates.add(Update.AlarmsChanged)

        updateDeliveryStopLatch(new.alarms)

        lastV1 = obj
        lastStatusReceivedAt = System.currentTimeMillis()

        val ret = StatusUpdate(
            changes = updates,
            previous = status,
            current = new,
            tbrDecodeInvalid = !ApexDriverLogic.shouldReconcileDecodedTbr(decodedTbr),
        )
        _status = new
        return ret
    }

    /**
     * Fold one frame's alarm slots into [deliveryStoppedByAlarm].
     *
     * Sets on the first frame that carries a motor-stopping alarm and clears only after
     * [ApexDriverLogic.DELIVERY_STOP_CLEAR_FRAMES] frames in a row carry none, because the pump
     * drops the alarm out of its slots for a frame at a time while the motor is still stopped.
     *
     * @param alarms the alarm slots of the frame being applied
     */
    private fun updateDeliveryStopLatch(alarms: List<PumpAlarm>): Unit = synchronized(deliveryStopLock) {
        if (ApexDriverLogic.anyStopsDelivery(alarms)) {
            if (!deliveryStoppedByAlarm)
                aapsLogger.warn(LTag.PUMP, "Pump reports an alarm that stops delivery - treating it as suspended")
            deliveryStoppedByAlarm = true
            cleanFramesSinceDeliveryStop = 0
            return
        }
        if (!deliveryStoppedByAlarm) return
        cleanFramesSinceDeliveryStop++
        if (cleanFramesSinceDeliveryStop >= ApexDriverLogic.DELIVERY_STOP_CLEAR_FRAMES) {
            aapsLogger.info(
                LTag.PUMP,
                "No delivery-stop alarm for $cleanFramesSinceDeliveryStop frames - the pump is delivering again"
            )
            deliveryStoppedByAlarm = false
            cleanFramesSinceDeliveryStop = 0
        }
    }

    /**
     * Battery level to store for a StatusV1 frame.
     *
     * StatusV1 only carries the pump's own coarse gauge (`Dead`/`Low`/`Medium`/`High`/`Full` →
     * 0/25/50/75/100). On a pump that also sends StatusV2 the exact, voltage-derived percentage
     * arrives 1.5-2 s later, so writing the coarse value here made every reader show 100 % in
     * between. A Nightscout upload landing in that window published 100 % while the cell was really
     * at 66 % - measured on 2026-08-10: 32 of 1856 uploads over one day.
     *
     * So keep the level we already have while an exact one exists and is still recent. All three
     * conditions are needed:
     *  - [lastV2] is null on a 4.9/4.10 pump, which never sends StatusV2, and on every cold start
     *    before the first V2 frame arrives. Keeping the old level there would pin the display at
     *    the "no status yet" fallback of 0 %, which reads as a critical battery everywhere.
     *  - with [ApexBooleanKey.CalculateBatteryPercentage] off the calculated percentage is not
     *    wanted at all, and [updateFromV2] then just copies whatever StatusV1 wrote.
     *  - the age bound matters because a StatusV2 that stops answering is no longer treated as an
     *    error (see `ApexService.getStatus`). Without it the display would freeze at an
     *    exact-looking value while the cell actually drains. Falling back to the coarse gauge is
     *    the honest state, and it marks itself approximate.
     *
     * The whole previous [BatteryLevel] is carried, not only its percentage, because `approximate`
     * drives the display: rewriting it as approximate on every V1 frame would make the pump status
     * line flip between "66 % (1.40 V)" and "66 %" about once a minute.
     *
     * Kept, but capped. An incoming `Low` or `Dead` gauge is the pump's own verdict on the cell and
     * has to reach the display even while the held exact value is still fresh, because three callers
     * can leave a V1 frame as the only thing applied for a whole cycle: `ApexService.checkPump`
     * reads StatusV1 alone before a bolus or TBR, a StatusV2 that times out is a warning rather than
     * a failure, and `onStatusV1` publishes about 1.6 s before the V2 of its own cycle. Waiting for
     * the next V2 there kept publishing a healthy-looking percentage while the pump beeped
     * LowBattery. So `Dead` writes 0 and `Low` caps at 25; a stored value already below the cap is
     * left where it is.
     *
     * The cap deliberately does not touch `approximate`. It stays whatever the stored level had,
     * because `ApexService.onBatteryChanged` skips any comparison that mixes an exact reading with a
     * coarse one: a Low-capped 25 % flagged approximate, followed by a new cell at an exact 100 %,
     * is exactly that mixed pair, and the PUMP_BATTERY_CHANGE event would be dropped.
     *
     * Recovery after a stale `Dead` V1 has overwritten a good exact value is slow, and it is not
     * "the next V2": that V2 reads [lastV1], which is still Dead, so [capByGauge] pins what it
     * publishes back to 0 as well. Only [BatteryLevel.uncappedPercentage] moves there - the voltage
     * map itself is no longer zeroed by the gauge, which is what lets the swap detector still see a
     * new cell. It takes a non-Dead V1 - which keeps the stored 0 %, but moves [lastV1] - and then a V2,
     * about two minutes when both frames arrive in order. With no V2 at all,
     * [EXACT_BATTERY_MAX_AGE_MS] makes the exact value unusable and a V1 writes its coarse gauge
     * again. That pessimistic window is accepted: erring low on a cell the pump itself called Dead
     * is the safe direction.
     */
    private fun batteryLevelForV1(obj: StatusV1): BatteryLevel {
        val exactIsUsable = lastV2 != null &&
            preferences.get(ApexBooleanKey.CalculateBatteryPercentage) &&
            System.currentTimeMillis() - lastV2At < EXACT_BATTERY_MAX_AGE_MS
        val gauge = obj.batteryLevel!!
        if (!exactIsUsable) return BatteryLevel(gauge.approximatePercentage, batteryLevel.voltage, true)

        val stored = batteryLevel
        // Cap the PUBLISHED percentage only. `uncappedPercentage` is carried through untouched by
        // copy(), which is what keeps the battery-swap detector honest - see [BatteryLevel].
        val capped = capByGauge(stored.percentage, gauge.approximatePercentage)
        return if (capped == stored.percentage) stored else stored.copy(percentage = capped)
    }

    /**
     * The pump's own coarse gauge as a ceiling on a percentage, never as a floor.
     *
     * `Dead` publishes 0 and `Low` publishes at most 25; a value already below the cap is left where
     * it is, and `Medium`/`High`/`Full` do not cap at all. A `null` gauge means no StatusV1 has been
     * seen yet - "unknown", neither Dead nor Low - and matches neither constant, so it caps nothing.
     *
     * Shared by both callers deliberately: [updateFromV2] applies it to a fresh StatusV2 reading,
     * [batteryLevelForV1] to the exact value being held between V2 frames, and a gauge that means
     * one thing on one path cannot come to mean another on the other.
     */
    private fun capByGauge(percentage: Int, coarsePercentage: Int?): Int = when (coarsePercentage) {
        PumpBatteryGauge.Dead.approximatePercentage -> 0
        PumpBatteryGauge.Low.approximatePercentage  -> minOf(percentage, PumpBatteryGauge.Low.approximatePercentage)
        else                                        -> percentage
    }

    /**
     * Convert the exact battery voltage reported by StatusV2 into a percentage over the configured
     * voltage window.
     *
     * The result is the raw map, uncapped: the pump's own gauge is applied on top of it by
     * [capByGauge], so that this function keeps answering the one question it can answer - what the
     * configured voltage window makes of this reading - and the caller decides what may be
     * published. `Dead` then publishes 0 % even at the 1.50 V the pump reports for a full cell (that
     * ceiling is a firmware artefact, not evidence the cell is good), and `Low` publishes at most
     * 25 %, which leaves a lower reading alone: a custom window of 1.20-1.50 V puts the 1.23 V of a
     * beeping cell at 10 %, and 10 % is the honest number.
     *
     * The gauge the caller passes to [capByGauge] is [lastV1], the last one applied, not necessarily
     * the V1 of the same status cycle: the frame workers run on a cached pool, so a V2 can be
     * applied while its own cycle's V1 is still in flight and be capped by the previous gauge
     * instead. The cap in [batteryLevelForV1] closes that window as soon as that V1 lands.
     */
    private fun calcFromVoltage(voltage: Double): Int {
        // Falls back to the key's own default rather than throwing on a stored name this build does not
        // know. Unlike the firmware setting this one is not fatal - processObject catches what the frame
        // workers throw and logs it - but the whole status frame would be abandoned at this point, so
        // the battery percentage and everything after it in that frame would be lost for a setting the
        // driver can simply read as "unknown".
        val storedBatteryType = preferences.get(ApexStringKey.CalcBatteryType)
        val batteryType = storedBatteryType.toEnumOrNull<BatteryType>() ?: BatteryType.Custom.also {
            aapsLogger.warn(LTag.PUMP, "Unknown battery type setting '$storedBatteryType' - using ${it.name}")
        }
        val lowVoltage = when (batteryType) {
            BatteryType.Custom -> preferences.get(ApexDoubleKey.BatteryLowVoltage)
            else -> batteryType.lowVoltage
        }
        val highVoltage = when (batteryType) {
            BatteryType.Custom -> preferences.get(ApexDoubleKey.BatteryHighVoltage)
            else -> batteryType.highVoltage
        }

        // Credits: Medtronic pump driver
        // Guard against a misconfigured custom range (low >= high) → division by zero / NaN.
        if (highVoltage <= lowVoltage) return 0
        val percent = (voltage - lowVoltage) / (highVoltage - lowVoltage)
        val percentInt = (percent * 100.0).toInt().coerceIn(0, 100)

        return percentInt
    }

    fun updateFromV2(obj: StatusV2): StatusUpdate {
        // With the calculation off StatusV2 has nothing of its own to publish - it carries whatever
        // coarse value StatusV1 wrote - and capping a coarse gauge by that same gauge is a no-op.
        val calculate = preferences.get(ApexBooleanKey.CalculateBatteryPercentage)
        val uncapped = if (calculate) calcFromVoltage(obj.batteryVoltage) else batteryLevel.percentage
        val percentage = if (calculate) capByGauge(uncapped, lastV1?.batteryLevel?.approximatePercentage) else uncapped

        val new = PumpStatus(
            batteryLevel = BatteryLevel(percentage, obj.batteryVoltage, false, uncapped),
            dateTime = dateTime,
            reservoirLevel = reservoirLevel,
            tbr = tbr,
            alarms = alarms,
            currentBasalPattern = currentBasalPattern,
            maxBasal = maxBasal,
            maxBolus = maxBolus,
            basal = basal,
            isAdvancedBolusEnabled = isAdvancedBolusEnabled,
            todayDeliveredUnits = todayDeliveredUnits,
        )
        val updates = mutableListOf<Update>()
        // Plain `if` for the same reason as in updateFromV1 — a subjectless `when` here is a
        // first-match chain that silently caps the list at one entry as soon as a branch is added.
        if (batteryLevel.voltage != new.batteryLevel.voltage) updates.add(Update.BatteryChanged)

        lastV2 = obj
        lastV2At = System.currentTimeMillis()

        val ret = StatusUpdate(
            changes = updates,
            previous = status,
            current = new,
        )
        _status = new
        return ret
    }

    data class BatteryLevel(
        val percentage: Int,
        val voltage: Double?, // v2+
        val approximate: Boolean,
        /**
         * [percentage] before the pump's coarse gauge capped it, for readers that need a measurement
         * rather than a ceiling. Defaults to [percentage], which is right everywhere no cap applies -
         * including the coarse fallback, where the gauge IS the measurement.
         *
         * Only `ApexService.onBatteryChanged` reads it, and it has to: a cap lowers [percentage]
         * without the cell having changed, so releasing that cap is a rise of up to the full cap
         * height, which is exactly the shape the swap detector looks for. On a custom 1.00-1.50 V
         * window a gauge that dips to Low at 1.27 V and comes back publishes 54 -> 25 -> 54, and the
         * detector's `cur - 26 > old` would log a battery change nobody made. Comparing the uncapped
         * figures leaves 54 -> 54 and keeps a real swap (46 -> 100) as loud as it ever was.
         *
         * Not part of what the user sees: [getBatteryLevel] and every other reader stay on
         * [percentage], so a Low or Dead gauge still shows the capped number everywhere.
         */
        val uncappedPercentage: Int = percentage,
    )

    data class Basal(
        val rate: Double,
    )

    data class TBR(
        val rate: Double?,
        val percentage: Int?,
        val isAbsolute: Boolean,
        val durationMinutes: Int,
        val elapsedMinutes: Int,
    )

    data class Treatment(
        val insulin: Double,
        val carbs: Int,
        val isSMB: Boolean,
        val id: Long?
    )

    data class InProgressBolus(
        var requestedDose: Double = 0.0,
        var currentDose: Double = 0.0,
        var temporaryId: Long = 0,
        var cancelled: Boolean = false,
        var detailedBolusInfo: DetailedBolusInfo,
        var treatment: Treatment,
        var failed: Boolean = false,
        /**
         * Whether [failed] rests on proof that the motor stopped - see `ApexDriverLogic.stopsDelivery`.
         *
         * [failed] alone does not mean "nothing was delivered". It is set whenever the bolus ended with
         * no progress frame reported, and the pump can end one on an alarm that says nothing about the
         * motor at all (a keypad fault, a screen fault, a code the table does not know). `onBolusFailed`
         * keeps the pending sync on exactly that ending, because the insulin may well have gone in.
         *
         * So the two have to be told apart: a proven stop is a bolus that did not happen, while an
         * unproven one is a bolus nobody can account for - which is what the "may be incomplete" card
         * exists to say. Defaults to true so a record that never went through `onBolusFailed` cannot be
         * mistaken for an unproven failure.
         */
        var deliveryStopProven: Boolean = true,
        var lockHistory: Boolean = true,
        /**
         * The "this bolus may be incomplete" card raised for THIS bolus, if one was raised.
         *
         * Kept so this bolus record knows whether it is the one that raised the card. In this
         * version of AAPS the id IS the card, so at most one "may be incomplete" card can stand at
         * a time and a later bolus raising one replaces the card of an earlier one. The
         * notification store has no read side here, so this stored handle is the only record of
         * who put the card up.
         *
         * Read and written only inside `ApexPumpPlugin.refreshUnconfirmedWarning`, which holds this
         * object's monitor. Two threads reach it - the one whose wait ran out and the one that later
         * matched the pump's history entry - and without that monitor the second could look for a
         * card the first had not finished posting.
         */
        var unconfirmedWarning: ApexNotifications.Handle? = null,
        /**
         * The confirmed amount the standing [unconfirmedWarning] names, so the card is replaced when
         * the pump reports a different one and left alone when it does not.
         */
        var unconfirmedWarningDose: Double? = null,
    )

    enum class Update {
        AlarmsChanged,
        BasalChanged,
        TBRChanged,
        ConstraintsChanged,
        UnadvisedSettingsChanged,
        BatteryChanged,
        ReservoirChanged,
    }

    data class PumpStatus(
        val dateTime: DateTime,
        val batteryLevel: BatteryLevel,
        val reservoirLevel: Double,
        val alarms: List<PumpAlarm>,
        val isAdvancedBolusEnabled: Boolean,
        val currentBasalPattern: Int,
        val maxBasal: Double,
        val maxBolus: Double,
        val tbr: TBR?,
        val basal: Basal?,
        /**
         * What the pump says it has delivered so far TODAY, in U. A running figure, not a daily total:
         * it grows through the day and resets at the pump's own midnight.
         *
         * Shown to the user and nothing else. It must not become a TDD record - the database wants
         * finished days, and writing a part-day figure into one would produce totals that are simply
         * wrong. Real TDDs come from the pump's own history object.
         */
        val todayDeliveredUnits: Double,
    ) {
        override fun toString() =
                "Date ${dateTime}, battery ${batteryLevel.percentage}, " +
                "reservoir ${reservoirLevel}, alarms ${alarms.joinToString(", ", "[", "]") { "${it.type.name} ${it.code}" }}, " +
                "maxBasal $maxBasal, maxBolus $maxBolus, today $todayDeliveredUnits, " +
                "TBR ${tbr?.rate}, basal ${basal?.rate}"

        fun getPumpStatusIcon(): String = when {
            alarms.isNotEmpty() -> "{fa-bell}"
            basal == null -> "{fa-ban}"
            else -> "{fa-check}"
        }

        fun getPumpStatus(rh: ResourceHelper): String = when {
            alarms.isNotEmpty() -> rh.gs(R.string.overview_pump_status_alarm)
            basal == null -> rh.gs(R.string.overview_pump_status_suspended)
            else -> rh.gs(R.string.overview_pump_status_normal)
        }

        fun getBatteryIcon(): String = when {
            batteryLevel.percentage > 90 -> "{fa-battery-4}"
            batteryLevel.percentage >= 75 -> "{fa-battery-3}"
            batteryLevel.percentage >= 50 -> "{fa-battery-2}"
            batteryLevel.percentage >= 25 -> "{fa-battery-1}"
            else -> "{fa-battery-0}"
        }

        fun getBatteryLevel(rh: ResourceHelper): String = if (batteryLevel.approximate)
            rh.gs(R.string.overview_pump_battery_approximate, batteryLevel.percentage)
        else
            rh.gs(R.string.overview_pump_battery_exact, batteryLevel.percentage, ((batteryLevel.voltage ?: 0.0) * 1000).roundToInt())

        fun getReservoirLevel(rh: ResourceHelper): String = rh.gs(R.string.overview_pump_reservoir, reservoirLevel)

        fun getTodayDelivered(rh: ResourceHelper): String = rh.gs(R.string.overview_pump_today_delivered, todayDeliveredUnits)

        fun getTBR(rh: ResourceHelper): String = if (tbr != null) {
            val diff = tbr.durationMinutes - tbr.elapsedMinutes
            val value = if (tbr.isAbsolute) tbr.rate else tbr.percentage
            if (diff >= 60)
                rh.gs(
                    if (tbr.isAbsolute)
                        R.string.overview_pump_tempbasal_h
                    else
                        R.string.overview_pump_tempbasal_percentage_h,
                    value, diff / 60, diff % 60
                )
            else
                rh.gs(
                    if (tbr.isAbsolute)
                        R.string.overview_pump_tempbasal
                    else
                        R.string.overview_pump_tempbasal_percentage,
                    value, diff
                )
        } else "-"

        fun getBasal(rh: ResourceHelper): String = if (basal != null) {
            rh.gs(R.string.overview_pump_basal, basal.rate)
        } else "-"
    }

    data class StatusUpdate(
        val changes: List<Update>,
        val previous: PumpStatus?,
        val current: PumpStatus,
        val tbrDecodeInvalid: Boolean = false,
    )

    companion object {

        /**
         * How long an exact, StatusV2-derived battery percentage stays trusted.
         *
         * The pump is asked for its status on every connect and on every heartbeat, so half an hour
         * without a single answered StatusV2 means the pump is not answering it at all. Past that we
         * go back to the coarse gauge instead of showing a frozen exact value. See
         * [batteryLevelForV1].
         */
        const val EXACT_BATTERY_MAX_AGE_MS = 30 * 60 * 1000L
    }
}