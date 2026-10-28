package app.aaps.pump.apex

import androidx.annotation.VisibleForTesting
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure driver rules. Kept out of [ApexService] so they can be broken in unit tests
 * without a BLE stack or a bound service.
 */
object ApexDriverLogic {

    const val STATUS_FRESH_WINDOW_MS = 25_000L

    /** How the pump answered a connection-password check on FFC2. */
    enum class PasswordReply {

        /** Accepted. Nothing else to do. */
        Accepted,

        /** Rejected. Do not send this password again until the user changes it. */
        Rejected,

        /**
         * An answer to a password CHANGE, which this driver never asks for.
         *
         * The pump tells a check from a change only by whether the two halves of the 12 bytes are
         * equal, and [passwordFrame] always builds them equal - so seeing this means the model of the
         * protocol is wrong, not that something went wrong.
         */
        ChangeAnswer,

        /** A byte outside the maker's own 0..3, or a notification that is not one byte long. */
        Unknown,
    }

    /**
     * The 12 bytes a password check puts on FFC1, or null when [digits] is not a password.
     *
     * The maker's app writes the six digits TWICE. That doubling is not decoration: a password change
     * writes twelve bytes as well, old followed by new, so the only thing in the frame that separates
     * a check from a change is whether the two halves are equal. Building the frame here, in one
     * place that cannot produce unequal halves, is what makes it impossible for this driver to change
     * the pump's password by accident.
     *
     * Null for anything that is not exactly six ASCII digits. The preference has a validator, but a
     * settings import writes preferences directly and never sees it, so the check has to live here
     * too - and returning null rather than throwing keeps a malformed value from reaching the pump
     * without taking the connection down with it.
     */
    @VisibleForTesting
    fun passwordFrame(digits: String): ByteArray? {
        if (digits.length != 6 || !digits.all { it in '0'..'9' }) return null
        return (digits + digits).toByteArray(Charsets.US_ASCII)
    }

    /**
     * The frame to send on this connection, or null to send nothing at all.
     *
     * Every reason the stored password ITSELF must not be sent lives here, in one pure place, so each
     * can be tested without a pump and none can be forgotten at a call site. The reasons that depend
     * on live state - already sent on this connection, and the command queue being busy - stay in
     * `ApexService.checkPumpPasswordOnce`, because they cannot be decided from these three values:
     *
     *  * the user has not said a password is set - the default, and then FFC0 is never opened;
     *  * the stored value is not six digits - a settings import writes preferences directly and never
     *    runs the preference validator, so this is the only check left between a malformed value and
     *    the pump;
     *  * this exact password was already rejected - resending it cannot start working, and a pump
     *    that counts attempts must not be fed by a reconnect loop. Changing the password is what makes
     *    the driver try again, because the memory holds the value rather than a flag.
     */
    @VisibleForTesting
    fun passwordToSend(hasPassword: Boolean, password: String, alreadyRejected: String?): ByteArray? {
        if (!hasPassword) return null
        if (password == alreadyRejected) return null
        return passwordFrame(password)
    }

    /**
     * What one FFC2 notification means.
     *
     * Total by construction: every byte value and every length maps to something, because this runs on
     * the BLE callback thread where an unhandled shape would be an exception in a place nothing
     * catches. The maker's own parser accepts only a single byte of 0..3; anything else is
     * [PasswordReply.Unknown] and is logged rather than acted on.
     */
    @VisibleForTesting
    fun passwordReply(value: ByteArray): PasswordReply {
        if (value.size != 1) return PasswordReply.Unknown
        return when (value[0].toInt()) {
            0    -> PasswordReply.Accepted
            1    -> PasswordReply.Rejected
            2, 3 -> PasswordReply.ChangeAnswer
            else -> PasswordReply.Unknown
        }
    }

    /** Which characteristic's descriptor a GATT `onDescriptorWrite` belongs to, as far as the gate cares. */
    enum class CallbackKind {
        /** The data channel (FFE4) CCC. This is the one that promotes the link. */
        Ffe4,

        /**
         * A null descriptor. The stack hands one back on some phones; before FFE4 is issued it means
         * nothing, after FFE4 is issued it is that write's completion (the only CCC write outstanding).
         */
        NullDescriptor,

        /** Any other characteristic - the FFC2 password-reply CCC, which only ever arms its own latch. */
        Other,
    }

    /** What an FFE4 `onDescriptorWrite` should do while the connection password gate is active. */
    enum class HandshakeAction {
        /** FFE4 opened: bring the link up. */
        Promote,

        /** FFE4 refused, and the password was not accepted: lock out and warn (card B). */
        RefuseAndLock,

        /** FFE4 refused, but the password WAS accepted: just drop the link, no lockout. */
        RefuseNoLock,

        /** Not this callback's business: a stale client, a late FFC2, or a pre-FFE4 stray. */
        Ignore,
    }

    /**
     * The promotion decision for one `onDescriptorWrite` while the gate is active, as a pure function so
     * the whole table is testable without a BLE stack. (The toggle-off path never reaches here: it
     * promotes through `ApexBluetooth.promotesConnection`, kept literal so it stays byte-identical.)
     *
     * Nothing promotes until FFE4 has been issued, and then only an FFE4 or - because the stack may
     * report the completion with a null descriptor - a null callback may promote, and only on success. A
     * refused FFE4 locks out unless the pump had already accepted the password ([PasswordReply.Accepted]),
     * which is the pump that has no password and opens the channel to anyone.
     */
    fun passwordHandshakeAction(
        ffe4Issued: Boolean,
        callbackIsCurrent: Boolean,
        callbackKind: CallbackKind,
        ffe4Success: Boolean,
        reply: PasswordReply?,
    ): HandshakeAction {
        if (!callbackIsCurrent) return HandshakeAction.Ignore
        if (!ffe4Issued) return HandshakeAction.Ignore
        return when (callbackKind) {
            CallbackKind.Other                             -> HandshakeAction.Ignore
            CallbackKind.Ffe4, CallbackKind.NullDescriptor ->
                if (ffe4Success) HandshakeAction.Promote
                else when (reply) {
                    PasswordReply.Accepted -> HandshakeAction.RefuseNoLock
                    else                   -> HandshakeAction.RefuseAndLock
                }
        }
    }

    /**
     * Whether the stored anti-hammer lockout is in force for the pump the user is pointing at now.
     *
     * Only when a lockout pair is set, matches the LIVE serial and password, and the password toggle is
     * still on. A mismatch - the user edited the password, changed the serial, or turned the toggle off
     * - means the old lockout no longer describes this pump, so it does not hold.
     */
    fun isPasswordLockout(
        lockoutSerial: String?,
        lockoutPassword: String?,
        liveSerial: String,
        livePassword: String,
        liveHasPassword: Boolean,
    ): Boolean =
        liveHasPassword &&
            lockoutSerial != null && lockoutPassword != null &&
            lockoutSerial == liveSerial && lockoutPassword == livePassword

    /**
     * Whether the reconnect loop may try again: not while destroyed or manually disconnected, and not
     * while [isPasswordLockout] holds for the live keys. The one predicate the loop, the service and the
     * plugin all ask.
     */
    fun shouldAttemptConnect(
        lockoutSerial: String?,
        lockoutPassword: String?,
        liveSerial: String,
        livePassword: String,
        liveHasPassword: Boolean,
        destroyed: Boolean,
        manualDisconnect: Boolean,
    ): Boolean =
        !destroyed && !manualDisconnect &&
            !isPasswordLockout(lockoutSerial, lockoutPassword, liveSerial, livePassword, liveHasPassword)

    /**
     * The six bytes a password SET puts on FFE9 (command `0x35` / `SetValue` / valueId 53), or null when
     * [digits] is not a six-digit password.
     *
     * Each digit is its own value byte `0x00..0x09`, not its ASCII code - the maker's packer reads
     * `Integer.valueOf(char)` per position. This is the vendor's real "change password" path (its Save
     * button calls it); the twelve-byte FFC1 `old+new` form is a dead screen in the maker's app and is
     * never built here. Reconstructed from the decompile, not yet byte-compared against a live capture,
     * so nothing sends it.
     */
    fun forceUpdatePasswordBytes(digits: String): ByteArray? {
        if (digits.length != 6 || !digits.all { it in '0'..'9' }) return null
        return ByteArray(6) { (digits[it] - '0').toByte() }
    }

    /**
     * Whether the cartridge is at or below the level the pump itself calls low.
     *
     * [thresholdU] is the pump's own setting, which arrives in every status frame, so the warning
     * appears at the level the user picked on the pump instead of a number this driver invented.
     *
     * A threshold of zero warns about nothing. The pump has a second low-reservoir criterion by time
     * left, so a zero here does not even prove its own warning is switched off, and the only thing
     * lost is a warning AAPS never had at all - while inventing a default would put a card in front
     * of the user at a level they never asked for.
     */
    fun isLowReservoir(levelU: Double, thresholdU: Int): Boolean = thresholdU > 0 && levelU <= thresholdU

    /**
     * The two cartridge warnings, decided independently.
     *
     * @param low the cartridge is at or below the level set on the pump itself
     * @param critical at or below the level the user set in AAPS, or `null` when that cannot be
     *   decided because no profile is running and the insulin concentration is unknown
     */
    data class ReservoirWarnings(val low: Boolean, val critical: Boolean?)

    /**
     * Answer both cartridge questions for one status frame.
     *
     * Two independent answers, not a pick between two cards. An earlier version chose one, and every
     * defect that followed came from that: it had to decide which card wins, and then to work out
     * whether a card missing from the screen meant the user had silenced it or the driver had taken
     * it down itself. Both cards standing is the simpler truth - one is silent and one rings, they
     * mean different things, and neither has to reason about the other.
     *
     * The level is passed TWICE, in two different units, and that is the point. The pump's own
     * threshold is a number the user set ON the pump, so it is compared against the pump's own
     * reading. `IntKey.OverviewResCritical` is an AAPS preference, and every other reader of it -
     * the overview status light, the watch face - compares it only after multiplying the cartridge
     * reading by the profile's insulin concentration. Comparing one level against both is right only
     * at U100: on U50 the loud card would wait for half the insulin the user asked to be warned at,
     * on U200 it would fire at twice the level. Two parameters with the units in their names is what
     * stops that being written again.
     *
     * @param levelPumpU the cartridge reading as the pump reports it
     * @param pumpLowThresholdPumpU the pump's own setting, out of the status frame, same units
     * @param levelIu the same reading converted with the profile's insulin concentration, or `null`
     *   when no profile is running. Not 1.0 as a stand-in: a guessed conversion moves the level the
     *   siren fires at for exactly the users the conversion exists for, so the answer is "cannot
     *   tell" and the caller leaves that card as it is.
     * @param criticalThresholdIu `IntKey.OverviewResCritical`, the level the user already picked for
     *   the red reservoir tint on the overview
     */
    fun reservoirWarnings(
        levelPumpU: Double,
        pumpLowThresholdPumpU: Int,
        levelIu: Double?,
        criticalThresholdIu: Int,
    ): ReservoirWarnings = ReservoirWarnings(
        low = isLowReservoir(levelPumpU, pumpLowThresholdPumpU),
        critical = levelIu?.let { isLowReservoir(it, criticalThresholdIu) },
    )

    /**
     * Whether an alarm gets a card at the urgent level. A catch-all on purpose: a code this driver
     * does not recognise should still be put in front of the user. It decides how the card LOOKS,
     * not whether it rings - see [ringsFor] - and not whether a running bolus is declared dead -
     * see [stopsDelivery].
     */
    fun isUrgent(alarm: Alarm): Boolean = when (alarm) {
        Alarm.NoError, Alarm.LowBattery, Alarm.LowReservoir, Alarm.CheckGlucose -> false
        else                                                                    -> true
    }

    /**
     * Alarms that get a sound. A closed list, deliberately - the opposite of [isUrgent].
     *
     * The sound is not a louder card: it plays on the alarm stream, loops until acknowledged and
     * bypasses Do Not Disturb. Attaching that to a catch-all would ring all night on a drifted pump
     * clock, a screen fault, a reserved code, or anything the enum simply does not know - none of
     * which stop insulin. The predictable answer to a false alarm at 03:00 is that the user mutes
     * the channel, and the occlusion alarm goes with it. So only conditions where insulin is
     * demonstrably not being delivered ring; everything else still raises a visible urgent card.
     */
    fun ringsFor(alarm: Alarm): Boolean = alarm in setOf(
        Alarm.NoDelivery, Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.DeadBattery,
        Alarm.MotorError, Alarm.MotorAbnormal, Alarm.MotorPowerAbnormal, Alarm.EncoderError,
        Alarm.BolusOrBasalDoseAbnormal, Alarm.PressureAbnormal, Alarm.FRAMError,
        // A code this driver cannot name may well be a delivery stop: the table is reverse
        // engineered and still has Reserved entries, so anything outside it lands here. Ringing is
        // the fail-loud direction, and it is safe because a re-raised card no longer brings the
        // sound back - a spurious ring can be silenced and stays silenced.
        Alarm.Unknown,
    )

    /**
     * Alarms that are proof the motor stopped.
     *
     * Two consumers, and the second one is why this list must not be narrowed casually. It decides
     * whether a bolus that reported no progress really delivered nothing - and, through
     * [anyStopsDelivery] and the latch it feeds, whether `ApexPump.isSuspended` reports the pump as
     * not delivering at all, which keeps the loop off it and refuses new temporary basals. The
     * "keeping the record costs nothing" argument below is about the first use only: for the second,
     * dropping a code from this list means AAPS goes on dosing into a pump whose motor has stopped.
     *
     * Deliberately NOT [isUrgent], which is a catch-all of 24 codes out of 28: a keypad fault, a
     * screen fault or a drifted clock says nothing about the motor, and treating those as proof
     * throws away the record that matches the pump's own history entry to the row AAPS already
     * wrote - after which the same insulin is inserted a second time.
     *
     * Deliberately NOT [ringsFor] either, even though the two lists read almost the same. That list
     * includes [Alarm.Unknown] and [Alarm.FRAMError] because ringing on an unnameable code is the
     * loud-and-safe direction. Here the safe direction is the opposite one: an unnameable code is
     * not evidence, and keeping the record costs nothing, because it is dropped by itself once it
     * goes stale.
     */
    fun stopsDelivery(alarm: Alarm): Boolean = alarm in setOf(
        Alarm.NoDelivery, Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.DeadBattery,
        Alarm.MotorError, Alarm.MotorAbnormal, Alarm.MotorPowerAbnormal, Alarm.EncoderError,
        Alarm.BolusOrBasalDoseAbnormal, Alarm.PressureAbnormal,
    )

    /** Whether any alarm in a status frame's slot list is proof the motor stopped. */
    fun anyStopsDelivery(alarms: List<PumpAlarm>): Boolean = alarms.any { stopsDelivery(it.type) }

    /**
     * How many consecutive status frames without a delivery-stop alarm end a stop.
     *
     * One clean frame is not enough. The slots hold the alarms the pump has not had acknowledged
     * yet, and they flicker: in a capture from 2026-08-31 the pump stopped on `ReservoirEmpty` at
     * 20:46:38, sent one single frame with an empty slot list at 20:58:57, and had the same alarm
     * back at 21:00:37 - while its own delivered-dose counter never moved through any of it. Acting
     * on that one frame would have declared the pump alive for two minutes and let the loop write a
     * temporary basal that no motor was running.
     *
     * Two, not more. When nothing else is talking to the pump a status frame is a heartbeat apart -
     * `HEARTBEAT_PERIOD_MINUTES` is 2, and the gaps between the 390 frames of 2026-08-31 have a
     * median of exactly 120 s. Commands pull extra reads in, so the mean over that same day was 88 s
     * and 68 of the gaps were under 30 s; an idle pump is the slow case and the one to size this by.
     * Two frames is therefore up to about four minutes before dosing resumes once the user clears the
     * alarm, less while the loop is busy, and every further frame would cost about two more minutes
     * of basal they do not get. Two still covers what was measured with a margin: the flicker there
     * was one frame long.
     */
    const val DELIVERY_STOP_CLEAR_FRAMES = 2

    /**
     * Whether this frame's raw temporary-basal fields are worth writing to the log.
     *
     * The driver decodes five values out of every StatusV1 - active, absolute, rate, duration,
     * elapsed - and prints none of them, so the only place they survive today is the raw hex dump,
     * which has to be decoded by hand and which fills the log fast enough to cut a diagnostic window
     * down to a few hours. Logging them decoded answers two open questions from an ordinary export:
     * whether the pump keeps the final elapsed value after a temp is cancelled on its keypad, and
     * whether the elapsed counter survives a suspend, a battery change or a cartridge change.
     *
     * Only on a transition, plus one exception. `elapsedMinutes` ticking UP is not a transition - it
     * moves on almost every frame, it was the reason 114 of 128 frames in one capture reported a TBR
     * change, and logging it would drown the very moments this exists to catch. Elapsed moving
     * BACKWARDS is a transition, because the pump only counts forwards.
     *
     * Backwards has two causes and the line does not distinguish them: the counter was reset under a
     * temp that kept running, which is the question above, or a NEW temp replaced the old one with
     * the same rate and duration, where a fresh counter is correct and expected. A reader tells them
     * apart by what sits next to the line - a temp AAPS set has its own "Started TBR" entry moments
     * earlier, and one that appeared on its own does not.
     *
     * What this still does not catch, deliberately: a counter that FREEZES. Distinguishing a frozen
     * counter from a normal one needs a clock, because the pump counts whole minutes and frames arrive
     * every two, so equal elapsed on two consecutive frames is the ordinary case. A freeze is not
     * invisible though - it shows up at the end of the temp, where the transition that is logged
     * anyway carries an elapsed far below the duration.
     */
    fun shouldLogTbrTail(previous: ApexPump.TBR?, current: ApexPump.TBR?): Boolean = when {
        previous == null && current == null -> false
        previous == null || current == null -> true
        else                                ->
            previous.rate != current.rate ||
                previous.percentage != current.percentage ||
                previous.isAbsolute != current.isAbsolute ||
                previous.durationMinutes != current.durationMinutes ||
                current.elapsedMinutes < previous.elapsedMinutes
    }

    /**
     * Why core did not store a temporary basal the pump had already accepted.
     *
     * Only what the driver can prove. `syncTemporaryBasalWithPumpId` returns false for several
     * reasons and does not say which, so anything beyond these three would be a guess printed as a
     * fact - and core logs the wrong-pump case itself, at a level that survives an export.
     */
    enum class TbrSyncRefusal {
        /** No profile was running at that moment, so core refused outright and stored nothing. */
        NoProfile,

        /** A stop found no open row to close. */
        NothingToClose,

        /** Core refused for a reason the driver cannot see from here. */
        Unknown,
    }

    /** Label a refused temporary-basal write. */
    fun tbrStartRefusal(profileRunning: Boolean): TbrSyncRefusal =
        if (!profileRunning) TbrSyncRefusal.NoProfile else TbrSyncRefusal.Unknown

    /** Label a refused temporary-basal stop. */
    fun tbrStopRefusal(hadRunningRow: Boolean): TbrSyncRefusal =
        if (!hadRunningRow) TbrSyncRefusal.NothingToClose else TbrSyncRefusal.Unknown

    /** Delivery rate at or below [BOLUS_SLOW_RATE_LIMIT_U], in units per second. */
    const val BOLUS_RATE_SMALL_U_PER_S = 0.025

    /** Delivery rate above [BOLUS_SLOW_RATE_LIMIT_U], in units per second. */
    const val BOLUS_RATE_LARGE_U_PER_S = 0.05

    /** Dose at which the pump switches from the slow rate to the fast one. */
    const val BOLUS_SLOW_RATE_LIMIT_U = 1.0

    /** Low delivery speed is about five times slower than standard. */
    const val BOLUS_LOW_SPEED_FACTOR = 5

    /** A reconnect while a bolus runs costs about this long. */
    const val BOLUS_RECONNECT_ALLOWANCE_MS = 45_000L

    /** Three commands are sent for a bolus - StatusV1, StatusV2, Bolus - at up to 2.5 s each. */
    const val BOLUS_COMMANDS_ALLOWANCE_MS = 8_000L

    /**
     * How long the pump can still plausibly be delivering [insulin], in milliseconds.
     *
     * The pump has no "bolus finished" reply that can be waited on directly, so the driver has to
     * estimate. Kept here rather than inline at the call site because a second reader is coming - the
     * bolus watchdog, which needs the same estimate plus its own margin - and two copies of this
     * arithmetic would drift apart without anyone noticing.
     *
     * @param lowSpeed the pump's Low delivery speed setting, which is about five times slower.
     */
    fun bolusDeliveryMs(insulin: Double, lowSpeed: Boolean): Long {
        val perSecond = if (insulin <= BOLUS_SLOW_RATE_LIMIT_U) BOLUS_RATE_SMALL_U_PER_S else BOLUS_RATE_LARGE_U_PER_S
        val speedFactor = if (lowSpeed) BOLUS_LOW_SPEED_FACTOR else 1
        // Rounded to whole seconds before scaling, exactly as the call site did before this moved here.
        val deliverySeconds = ((insulin / perSecond) * speedFactor).roundToLong()
        return deliverySeconds * 1000 + BOLUS_RECONNECT_ALLOWANCE_MS + BOLUS_COMMANDS_ALLOWANCE_MS
    }

    /**
     * Did a bolus end without the pump ever confirming the whole of it?
     *
     * The driver waits either for the pump's own history entry to be matched or for [bolusDeliveryMs]
     * to run out, and the wait itself cannot say which happened. What it can say is how much the pump
     * reported against how much was asked for, which is the same test the Dana drivers use.
     *
     * Not the same thing as a failure, and deliberately not reported as one: a shortfall may equally be
     * insulin that went in with its confirmation lost on a dropped link. It is a reason to tell the
     * user, not a reason for the loop to try again.
     *
     * @param cancelled a bolus the user stopped delivered less on purpose, and how much is known.
     * @param provenFailure the bolus ended AND there is proof the motor stopped, so there is nothing
     *   uncertain left to say. `InProgressBolus.failed` on its own is NOT that proof: it is also set
     *   when the bolus ended on an alarm that says nothing about the motor, and that ending is the one
     *   this card exists for - see `ApexPump.InProgressBolus.deliveryStopProven`.
     * @param bolusStep the pump's own dose granularity; a full delivery lands within one step of the
     *   request, because the pump rounds the request onto that grid.
     */
    fun isBolusShortfall(requested: Double, delivered: Double, cancelled: Boolean, provenFailure: Boolean, bolusStep: Double): Boolean =
        !provenFailure && !cancelled && requested - delivered >= bolusStep - DOSE_COMPARE_TOLERANCE_U

    /**
     * Slack for comparing two doses that are each a multiple of the pump's 0.025 U step.
     *
     * Neither 0.025 nor its multiples are exact in binary, so a difference that is one step on paper
     * lands on either side of the step depending on the pair: 1.0 - 0.975 comes out just above,
     * 2.0 - 1.975 just below. Without this the "one step short" boundary was decided by rounding
     * noise, and a test could pass on one pair while the same case failed on another.
     */
    const val DOSE_COMPARE_TOLERANCE_U = 1e-6

    /** One 0.025 U step plus a little, so encoding noise does not look like a different TBR. */
    const val TBR_RATE_MATCH_U = 0.026

    const val TBR_PERCENT_MATCH = 1.0

    /**
     * Pump elapsed time is a whole minute. AAPS remaining time is wall-clock.
     * Three minutes covers that rounding plus a slow status read.
     */
    const val TBR_REMAINING_MATCH_MIN = 3

    /**
     * How long a TBR row in the database is trusted over a status frame that disagrees with it.
     *
     * The pump can answer with a frame that does not show a TBR the driver has just set and the pump
     * has already accepted. Correcting from such a frame would cancel a TBR the pump is really
     * running, or write the old rate over the new row.
     *
     * Cost: a keypad cancel of that same young AAPS row is delayed until the row is older than this
     * window. A keypad TBR while AAPS has no row is still started immediately.
     */
    const val TBR_SETTLE_MS = 60_000L

    /**
     * After AAPS drops a TBR (cancel or the SQL end), a pump frame can still show it for a heartbeat.
     * Starting that frame as a new keypad TBR would undo the cancel or cut the old history row.
     *
     * Measured from the actual drop time in [lastAapsTbr.end], not from the planned end.
     * Callers must clip with [tbrDroppedAt] when AAPS stops a TBR early.
     */
    const val TBR_END_GRACE_MS = TBR_REMAINING_MATCH_MIN * 60_000L

    /**
     * Slop for the leftover-vs-new check in [isSameTbrEpisode]. A leftover frame keeps counting
     * elapsed from the old start, so its elapsed tracks the old row's age; a keypad TBR restarted
     * right after the cancel counts from its own start and reads visibly younger. The slop covers
     * the pump's whole-minute elapsed rounding plus a slow read.
     */
    const val TBR_EPISODE_ELAPSED_SLOP_MS = 90_000L

    /**
     * Longest TBR the pump type advertises ([app.aaps.core.data.pump.defs.PumpType.APEX_TRUCARE_III]
     * tbrSettings.maxDuration). A StatusV1 duration is a UShort of minutes; 65535 min is not a real temp.
     */
    const val TBR_MAX_DURATION_MIN = 24 * 60

    /** Keypad percent TBR above this is treated as garbage, not a running temp. */
    const val TBR_MAX_PERCENT = 500

    /**
     * Hard ceiling for an absolute TBR in U/h. Same as Apex max basal (25 U/h).
     * Compared in raw 0.025 U steps so encoding noise does not need a float slop on this cap.
     */
    const val TBR_MAX_ABSOLUTE_U = 25.0

    /** [TBR_MAX_ABSOLUTE_U] in StatusV1 steps of 0.025 U/h. 1000 is 25.0 U/h; 1001 is 25.025 U/h. */
    const val TBR_MAX_ABSOLUTE_RAW = 1000

    /** Same sentinel StatusV1 uses for "no current basal". */
    const val TBR_RATE_SENTINEL = 65535

    sealed class DecodedStatusTbr {
        data object None : DecodedStatusTbr()
        data class Running(val tbr: ApexPump.TBR) : DecodedStatusTbr()
        data object Invalid : DecodedStatusTbr()
    }

    /**
     * Decode StatusV1 TBR fields for reconciliation.
     *
     * [DecodedStatusTbr.None] means the pump has no TBR (including a finished one).
     * [DecodedStatusTbr.Invalid] is garbage in the TBR bytes. Display may keep the last good TBR via
     * [resolvedTbr]; [shouldReconcileDecodedTbr] is false, so those bytes must not reach [tbrSyncAction].
     * Mapping Invalid to null would look like Stop.
     */
    fun decodeStatusTbr(
        activeRaw: Int,
        isAbsoluteRaw: Int,
        rateRaw: Int,
        durationMinutes: Int,
        elapsedMinutes: Int,
    ): DecodedStatusTbr {
        // Protocol flags are 0 or 1. Byte.toBoolean() maps 2..255 to true and would look like a live TBR.
        if (activeRaw != 0 && activeRaw != 1) return DecodedStatusTbr.Invalid
        if (activeRaw == 0) return DecodedStatusTbr.None
        if (durationMinutes <= 0 || durationMinutes > TBR_MAX_DURATION_MIN) return DecodedStatusTbr.Invalid
        if (elapsedMinutes < 0 || elapsedMinutes > durationMinutes) return DecodedStatusTbr.Invalid
        if (elapsedMinutes == durationMinutes) return DecodedStatusTbr.None
        if (rateRaw == TBR_RATE_SENTINEL || rateRaw < 0) return DecodedStatusTbr.Invalid
        if (isAbsoluteRaw != 0 && isAbsoluteRaw != 1) return DecodedStatusTbr.Invalid
        if (isAbsoluteRaw == 1) {
            if (rateRaw > TBR_MAX_ABSOLUTE_RAW) return DecodedStatusTbr.Invalid
            val rateU = rateRaw * 0.025
            return DecodedStatusTbr.Running(ApexPump.TBR(rateU, null, true, durationMinutes, elapsedMinutes))
        }
        if (rateRaw > TBR_MAX_PERCENT) return DecodedStatusTbr.Invalid
        return DecodedStatusTbr.Running(ApexPump.TBR(null, rateRaw, false, durationMinutes, elapsedMinutes))
    }

    /**
     * Map a decode onto pump display state. Invalid keeps [previous] so the UI does not flash empty.
     * Do not pass this result to [tbrSyncAction] when [shouldReconcileDecodedTbr] is false:
     * a kept TBR with frozen elapsed can become a duplicate Start, and null can become Stop.
     */
    fun resolvedTbr(decoded: DecodedStatusTbr, previous: ApexPump.TBR?): ApexPump.TBR? = when (decoded) {
        is DecodedStatusTbr.None     -> null
        is DecodedStatusTbr.Running  -> decoded.tbr
        is DecodedStatusTbr.Invalid  -> previous
    }

    /** Invalid TBR bytes must not be fed to [tbrSyncAction]. None and Running still reconcile. */
    fun shouldReconcileDecodedTbr(decoded: DecodedStatusTbr): Boolean =
        decoded !is DecodedStatusTbr.Invalid

    fun isSerialConfigured(serial: String): Boolean = serial.matches(Regex("^\\d{8}$"))

    /**
     * TBR duration the pump will actually run: 15-minute steps, rounded down.
     * Null when that would be zero — the caller must refuse, not send a 0-length TBR.
     */
    fun alignedTbrDurationMinutes(requestedMinutes: Int): Int? {
        if (requestedMinutes <= 0) return null
        val aligned = requestedMinutes - requestedMinutes % 15
        return aligned.takeIf { it > 0 }
    }

    /**
     * Whether the last StatusV1 is still new enough to skip another read.
     *
     * [lastReceivedAt] is the phone-clock moment the frame arrived, not the pump RTC.
     * A pump clock that sits a few seconds ahead of the phone used to make every later
     * read look "fresh" forever.
     */
    fun isStatusFresh(lastReceivedAt: Long, nowMillis: Long, windowMs: Long = STATUS_FRESH_WINDOW_MS): Boolean =
        lastReceivedAt > 0L && nowMillis >= lastReceivedAt && nowMillis - lastReceivedAt < windowMs

    /**
     * Compare a 48-slot AAPS profile to the rates cached from the pump.
     * The half-step window is the same one [ApexPumpPlugin.isThisProfileSet] used on the live read.
     */
    fun basalProfileMatches(requested: List<Double>, pump: List<Double>): Boolean {
        if (requested.size != 48 || pump.size != 48) return false
        for (i in 0 until 48) {
            val precision = DoseStepSize.Apex.getStepSizeForAmount(requested[i]) / 2
            if (abs(pump[i] - requested[i]) > precision) return false
        }
        return true
    }

    sealed class TbrSyncAction {
        data object None : TbrSyncAction()
        data object Stop : TbrSyncAction()
        data class Start(
            val timestamp: Long,
            val durationMs: Long,
            val rate: Double,
            val isAbsolute: Boolean,
        ) : TbrSyncAction()
    }

    /**
     * Reconcile the TBR the pump reports with the TBR AAPS already has in the database.
     *
     * Rules, in this order:
     *  - a pump TBR that matches the row AAPS has must not insert a second row,
     *  - a row younger than [TBR_SETTLE_MS] wins over a frame that disagrees with it,
     *  - a pump TBR that is the same episode as [lastAapsTbr] (just cancelled, or SQL just dropped
     *    it) must not be started again,
     *  - anything else is taken from the pump: a keypad TBR, or a TBR the pump ended on its own.
     *
     * [lastAapsTbr] is the last AAPS TBR we knew, including after it left [expected]. After an
     * early cancel or Stop its [PumpSync.PumpState.TemporaryBasal.end] must be the drop time
     * (see [tbrDroppedAt]). Pass null when AAPS never had a TBR in this session.
     *
     * [insulinConcentration] converts pump-native absolute rates into the units PumpSync stores
     * ([app.aaps.core.interfaces.pump.PumpRate.iU] does the same on the write path). The rest of
     * this driver does not support a concentration other than 1.0 - commands still encode plain
     * units - this only keeps the comparisons consistent with what the database returns.
     */
    fun tbrSyncAction(
        pumpTbr: ApexPump.TBR?,
        expected: PumpSync.PumpState.TemporaryBasal?,
        nowMillis: Long,
        lastAapsTbr: PumpSync.PumpState.TemporaryBasal? = null,
        insulinConcentration: Double = 1.0,
    ): TbrSyncAction {
        val pump = pumpTbr?.takeIf { pumpRemainingMinutes(it) > 0 && pumpRate(it) != null }
        val aaps = expected?.takeIf { expectedRemainingMinutes(it, nowMillis) > 0 }
        if (aaps != null) {
            if (pump != null && tbrMatches(pump, aaps, nowMillis, insulinConcentration)) return TbrSyncAction.None
            // A row this young may simply be missing from the frame in hand. A row dated in the
            // future (phone clock jumped back) is not young, it is wrong, so it is corrected.
            if (nowMillis - aaps.timestamp in 0 until TBR_SETTLE_MS) return TbrSyncAction.None
        }
        if (pump == null) return if (aaps == null) TbrSyncAction.None else TbrSyncAction.Stop
        if (isSameTbrEpisode(pump, lastAapsTbr, nowMillis, insulinConcentration)) return TbrSyncAction.None
        return startFromPump(pump, nowMillis)
    }

    @VisibleForTesting
    fun pumpRemainingMinutes(tbr: ApexPump.TBR): Int =
        (tbr.durationMinutes - tbr.elapsedMinutes).coerceAtLeast(0)

    /**
     * Remaining minutes of the AAPS row, counted the way the pump counts: any time left at all is
     * at least one minute, and only a finished TBR is zero.
     *
     * Rounding down instead looked like "AAPS has no TBR" during the last 59 seconds, while the pump
     * still reported one minute left. That inserted a second row, with a new pumpId, near the end of
     * every TBR AAPS had set itself.
     */
    @VisibleForTesting
    fun expectedRemainingMinutes(expected: PumpSync.PumpState.TemporaryBasal, nowMillis: Long): Long {
        val remainingMs = expected.timestamp + expected.duration - nowMillis
        if (remainingMs <= 0L) return 0L
        return (remainingMs + 59_999L) / 60_000L
    }

    private fun pumpRate(tbr: ApexPump.TBR): Double? =
        if (tbr.isAbsolute) tbr.rate else tbr.percentage?.toDouble()

    /**
     * Pump TBR rate in the units the database stores. Absolute rates are multiplied by the insulin
     * concentration - the write path does the same via [app.aaps.core.interfaces.pump.PumpRate.iU] -
     * percent rates are unit-free. Comparing the raw pump rate against the stored rate made every
     * own TBR look foreign for a concentration other than 1.0 and re-started it each cycle.
     */
    private fun pumpRateInDbUnits(tbr: ApexPump.TBR, insulinConcentration: Double): Double? =
        if (tbr.isAbsolute) tbr.rate?.times(insulinConcentration) else tbr.percentage?.toDouble()

    private fun tbrMatches(pump: ApexPump.TBR, expected: PumpSync.PumpState.TemporaryBasal, nowMillis: Long, insulinConcentration: Double): Boolean {
        if (pump.isAbsolute != expected.isAbsolute) return false
        val rate = pumpRateInDbUnits(pump, insulinConcentration) ?: return false
        // The match window is one pump step; in database units that step scales with the concentration.
        val rateWindow = if (pump.isAbsolute) TBR_RATE_MATCH_U * insulinConcentration else TBR_PERCENT_MATCH
        if (abs(rate - expected.rate) > rateWindow) return false
        return abs(pumpRemainingMinutes(pump) - expectedRemainingMinutes(expected, nowMillis)) <= TBR_REMAINING_MATCH_MIN
    }

    /**
     * Cut [tbr] so [PumpSync.PumpState.TemporaryBasal.end] is the moment AAPS actually dropped it.
     * Never lengthens an already clipped row.
     *
     * Without this, [isSameTbrEpisode] treats a planned end still in the future as "just dropped"
     * and hides a same-rate keypad TBR until that planned end.
     */
    fun tbrDroppedAt(tbr: PumpSync.PumpState.TemporaryBasal, droppedAtMillis: Long): PumpSync.PumpState.TemporaryBasal {
        val end = min(tbr.end, droppedAtMillis)
        val duration = max(1L, end - tbr.timestamp)
        return if (duration == tbr.duration) tbr else tbr.copy(duration = duration)
    }

    /**
     * Snapshot of the pump TBR AAPS just cancelled, clipped to [droppedAtMillis].
     * Null when the last status had no running TBR.
     */
    fun lastAapsFromPumpTbr(pump: ApexPump.TBR?, droppedAtMillis: Long, insulinConcentration: Double = 1.0): PumpSync.PumpState.TemporaryBasal? {
        if (pump == null) return null
        if (pumpRemainingMinutes(pump) <= 0) return null
        // The snapshot mimics a database row, so the rate must be in database units.
        val rate = pumpRateInDbUnits(pump, insulinConcentration) ?: return null
        val elapsedMs = pump.elapsedMinutes.coerceAtLeast(0).toLong() * 60_000L
        val timestamp = min(droppedAtMillis, max(0L, droppedAtMillis - elapsedMs))
        return tbrDroppedAt(
            PumpSync.PumpState.TemporaryBasal(
                timestamp = timestamp,
                duration = pump.durationMinutes.toLong() * 60_000L,
                rate = rate,
                isAbsolute = pump.isAbsolute,
                type = PumpSync.TemporaryBasalType.NORMAL,
                id = 0L,
                pumpId = timestamp,
            ),
            droppedAtMillis,
        )
    }

    /**
     * True when the pump is still showing the TBR AAPS just cancelled or that SQL just dropped.
     * Starting it again would undo the cancel or cut the old history row and insert a new full temp.
     *
     * [PumpSync.PumpState.TemporaryBasal.end] is the actual drop time, not the planned end,
     * when the caller used [tbrDroppedAt].
     */
    @VisibleForTesting
    fun isSameTbrEpisode(
        pump: ApexPump.TBR,
        lastAaps: PumpSync.PumpState.TemporaryBasal?,
        nowMillis: Long,
        insulinConcentration: Double = 1.0,
    ): Boolean {
        if (lastAaps == null) return false
        if (pump.isAbsolute != lastAaps.isAbsolute) return false
        val rate = pumpRateInDbUnits(pump, insulinConcentration) ?: return false
        val rateWindow = if (pump.isAbsolute) TBR_RATE_MATCH_U * insulinConcentration else TBR_PERCENT_MATCH
        if (abs(rate - lastAaps.rate) > rateWindow) return false
        val elapsedMs = pump.elapsedMinutes.coerceAtLeast(0).toLong() * 60_000L
        // A leftover frame keeps counting elapsed from the old start, so elapsed tracks the old
        // row's age. A keypad TBR restarted right after the cancel counts from its own start and
        // reads visibly younger - start it instead of hiding it for the whole end grace. Frames
        // from before the command never reach this check (the epoch guard in ApexService drops
        // them), so the age comparison only ever sees post-command frames.
        if (elapsedMs + TBR_EPISODE_ELAPSED_SLOP_MS < nowMillis - lastAaps.timestamp) return false
        val pumpStart = min(nowMillis, max(0L, nowMillis - elapsedMs))
        if (abs(pumpStart - lastAaps.timestamp) > TBR_REMAINING_MATCH_MIN * 60_000L) return false
        return nowMillis - lastAaps.end < TBR_END_GRACE_MS
    }

    private fun startFromPump(pump: ApexPump.TBR, nowMillis: Long): TbrSyncAction.Start {
        val elapsedMs = pump.elapsedMinutes.coerceAtLeast(0).toLong() * 60_000L
        val timestamp = min(nowMillis, max(0L, nowMillis - elapsedMs))
        return TbrSyncAction.Start(
            timestamp = timestamp,
            durationMs = pump.durationMinutes.toLong() * 60_000L,
            rate = pumpRate(pump)!!,
            isAbsolute = pump.isAbsolute,
        )
    }
}
