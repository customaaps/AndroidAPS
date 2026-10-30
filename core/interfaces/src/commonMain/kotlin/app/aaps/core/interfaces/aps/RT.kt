package app.aaps.core.interfaces.aps

import app.aaps.core.data.datetime.parseIsoToEpochMillisOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable
data class RT(
    var algorithm: APSResult.Algorithm = APSResult.Algorithm.UNKNOWN,
    var runningDynamicIsf: Boolean,
    @Serializable(with = TimestampToIsoSerializer::class)
    var timestamp: Long? = null,
    val temp: String = "absolute",
    var bg: Double? = null,
    var tick: String? = null,
    var eventualBG: Double? = null,
    var targetBG: Double? = null,
    var snoozeBG: Double? = null, // AMA only
    var insulinReq: Double? = null,
    var carbsReq: Int? = null,
    var carbsReqWithin: Int? = null,
    var units: Double? = null, // micro bolus
    @Serializable(with = TimestampToIsoSerializer::class)
    var deliverAt: Long? = null, // The time at which the micro bolus should be delivered
    var sensitivityRatio: Double? = null, // autosens ratio (fraction of normal basal)
    @Serializable(with = StringBuilderSerializer::class)
    var reason: StringBuilder = StringBuilder(),
    var duration: Int? = null,
    var rate: Double? = null,
    var predBGs: Predictions? = null,
    var COB: Double? = null,
    var IOB: Double? = null,
    var variable_sens: Double? = null,
    var isfMgdlForCarbs: Double? = null, // used to pass to AAPS client

    // Display-only snapshot from this calculation. Optional for older stored/device-status results.
    var profileIsfMgdl: Double? = null,
    var effectiveIsf: Double? = null,
    var profileCarbRatio: Double? = null,
    var carbRatio: Double? = null,

    var carbRatioReason: String? = null,
    var boostTier: String? = null,
    var boostV5_active: Boolean? = null,
    var boostV5_state: String? = null,
    // Current Boost V5 cycle telemetry for overview presentation and device status.
    var boostV5_score: Double? = null,
    var boostV5_age: Int? = null,
    var boostV5_budget: Double? = null,
    var boostV5_actionMult: Double? = null,
    var boostV5_finalDose: Double? = null,
    var boostActive: Boolean? = null,
    var fastCarbProtection: Boolean? = null,
    var dynamicISF: Double? = null,
    var predictionISF: Double? = null,
    var sensNormalTarget: Double? = null,
    var tdd: Double? = null,
    var tddRatio: Double? = null,
    var insulinReqPctEffective: Double? = null,
    var deltaAcceleration: Double? = null,
    var boostProfileSwitch: Int? = null,
    var mlHypoRisk: Double? = null,
    var mlMealLikely: Double? = null,
    var minGuardBG: Double? = null,
    var minGuardBGSource: String? = null,
    var boostVersion: String? = null,
    // Counterfactual diagnostics. No engine or Loop reads these to size a dose.
    var isfShadow_ratioRaw: Double? = null,
    var isfShadow_ratioEma: Double? = null,
    var isfShadow_warmup: Double? = null,
    var isfShadow_variableSens: Double? = null,
    var isfShadow_deltaPct: Double? = null,
    var isfShadow_insulinReq: Double? = null,
    var isfShadow_microBolus: Double? = null,
    var boostVwa_blend: Double? = null,
    var boostVwa_projection: Double? = null,
    var boostVwa_expected: Double? = null,
    var boostVwa_delivered: Double? = null,
    var boostVwa_dayFraction: Double? = null,
    var boostVwa_calibratedTdd: Double? = null,
    var boostVwa_curveDays: Int? = null,
    var boostVwa_usedPrevDay: Boolean? = null,

    var consoleLog: MutableList<String>? = null,
    var consoleError: MutableList<String>? = null,

    // Source activity-load shadow: diagnostics only, never applied to dosing.
    var boostActivityLoad_baselineSteps: Int? = null,
    var boostActivityLoad_lastDaySteps: Int? = null,
    var boostActivityLoad_ratio: Double? = null,
    var boostActivityLoad_wouldDeltaIsfPct: Double? = null,
    var boostActivityLoad_source: String? = null,
    var boostActivitySource_resolved: String? = null,
    var boostActivitySource_states: String? = null,
    var boostActivitySource_bridge: String? = null,
    var boostActivityLoad_stepsToday: Int? = null,
    var boostActivityLoad_stepsSource: String? = null,
    var boostActivityLoad_intradayRatio: Double? = null,
    var boostActivityLoad_intradayDeltaIsfPct: Double? = null,

    // HR telemetry
    var hrBpmLatest: Double? = null,                 // most recent HR reading at cycle time
    var hrBpmAvg5m: Double? = null,                  // duration-weighted average over last 5 min
    var hrBpmAvg15m: Double? = null,                 // duration-weighted average over last 15 min
    var hrReadingsCount15m: Int? = null,             // number of HR records seen in 15-min window
    var hrSource_resolved: String? = null,           // live HR source: garmin | worn:<model> | hc | null if feed died
    var hrSource_states: String? = null,             // per-source "src(fresh,count,ageMin)", best-trust first

    // Sleep state + learning
    var sleepState: String? = null,                  // AWAKE | PRE_SLEEP | SLEEPING
    var sleepStateEnteredAtMs: Long? = null,         // when current sleep state was entered (UTC ms)
    var sleepEntryReason: String? = null,            // "hr" | "drought" while SLEEPING; null otherwise
    var sleepLearnedStartMin: Int? = null,           // circular-mean sleep-onset clock-min (0..1439)
    var sleepLearnedWakeMin: Int? = null,            // circular-mean wake clock-min (0..1439)
    var sleepLearnedDurationMin: Int? = null,        // mean sleep duration (min)
    var sleepLearnedSessionCount: Int? = null,       // sessions in 28-day window
    var hrLearnedRestingBpm: Int? = null,            // true resting (deep-sleep floor)
    var hrLearnedDaytimeBpm: Int? = null,            // active-baseline (used by exercise calcs)
) {

    fun serialize() = Json.encodeToString(serializer(), this)

    object StringBuilderSerializer : KSerializer<StringBuilder> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringBuilder", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: StringBuilder) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): StringBuilder {
            return StringBuilder().append(decoder.decodeString())
        }
    }

    object TimestampToIsoSerializer : KSerializer<Long> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongToIso", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Long) {
            encoder.encodeString(toISOString(value))
        }

        override fun deserialize(decoder: Decoder): Long {
            return fromISODateString(decoder.decodeString())
        }

        /**
         * Was joda's `ISODateTimeFormat.dateTimeParser()`. The replacement accepts the same shapes -
         * this parses device status written by other Nightscout uploaders, not only what AAPS wrote,
         * so `+0200` without a colon and offset-less values have to keep working.
         *
         * It still throws on input it cannot read, as joda did. A silent epoch-0 timestamp inside an
         * APS result would be worse than a loud failure.
         */
        fun fromISODateString(isoDateString: String): Long =
            parseIsoToEpochMillisOrNull(isoDateString)
                ?: throw IllegalArgumentException("Invalid format: $isoDateString")

        /**
         * `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC, always with three fractional digits.
         *
         * Built explicitly rather than with `Instant.toString()`, which drops trailing zeros and
         * omits the fraction altogether on a whole second.
         */
        private val isoOut = LocalDateTime.Format {
            year(); char('-'); monthNumber(); char('-'); day()
            char('T')
            hour(); char(':'); minute(); char(':'); second()
            char('.'); secondFraction(3)
        }

        /**
         * Locale independent on purpose. A formatter that resolves its calendar from the locale
         * breaks the wire format: on a phone set to Thai it selects the Buddhist calendar and writes
         * the year as 2569 instead of 2026 - a timestamp 543 years in the future.
         * `RtIsoStringParityTest` pins the output.
         */
        fun toISOString(date: Long): String =
            isoOut.format(Instant.fromEpochMilliseconds(date).toLocalDateTime(TimeZone.UTC)) + "Z"
    }

    companion object {

        private val serializer = Json { ignoreUnknownKeys = true }
        fun deserialize(jsonString: String) = serializer.decodeFromString(serializer(), jsonString)
    }
}


