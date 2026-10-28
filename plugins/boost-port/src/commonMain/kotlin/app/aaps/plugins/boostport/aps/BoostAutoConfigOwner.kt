package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfig as Derive
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfigApply as Apply
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5OfflineDoubleKey as Knob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Sequential APS owner. Proposals and ledgers commit together after all history reads succeed. */
internal class BoostAutoConfigOwner(private val store: KeyValueStore,
    private val persistence: PersistenceLayer, private val tdd: TddCalculator,
    private val iso: (Long) -> String) {
    companion object {
        const val SCHEMA = "boost_v5_autoconfig_schema_version"
        const val REDRIVE_SCHEMA = "boost_v5_redrive_schema_version"
        const val LAST_REDRIVE = "boost_v5_autoconfig_last_redrive_ms"
        fun resolved(key: String) = "boost_v5_autoconfig_resolved_$key"
        val booleanKeys = listOf(BoostApsSettingKeys.ApsBoostV5FastCarbConfirm,
            BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm, BoostApsSettingKeys.ApsBoostV5VelocityBudgetActive,
            BoostApsSettingKeys.ApsBoostV5PrimerTbrFallback)
        val ownedKeys = listOf(BoostApsSettingKeys.IntValue(SCHEMA, 0),
            BoostApsSettingKeys.IntValue(REDRIVE_SCHEMA, 0), BoostApsSettingKeys.LongValue(LAST_REDRIVE, 0L)) +
            (Apply.managedDoubleKeys.map { it.key } + booleanKeys.map { it.key }).map {
                BoostApsSettingKeys.BooleanValue(resolved(it), false) }
    }

    suspend fun evaluate(now: Long, maxIob: Double, maxBolus: Double): BoostAutoConfigNotice? {
        require(now > 0 && maxIob.isFinite() && maxIob >= 0 && maxBolus.isFinite() && maxBolus >= 0)
        val original = store.getAll()
        val pending = linkedMapOf<String, Any?>()
        fun flag(key: String): Boolean = if (pending.containsKey(key)) pending[key] as? Boolean ?: false
            else store.getBoolean(key, false)
        fun number(key: String): Double? = if (pending.containsKey(key)) (pending[key] as? Number)?.toDouble()
            else if (store.contains(key)) store.getDouble(key, 0.0).also { require(it.isFinite()) { "invalid Boost config knob $key" } } else null
        fun text(key: String): String = if (pending.containsKey(key)) pending[key] as? String ?: "" else store.getString(key, "")
        fun done(key: String) = flag(resolved(key))
        fun mark(key: String) { pending[resolved(key)] = true }
        fun ledger(key: String): MutableMap<String, Double> {
            val raw = text(key)
            if (raw.isEmpty()) return mutableMapOf()
            require(raw.length <= 1_048_576) { "oversize Boost config ledger" }
            return Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive.double.also { require(it.isFinite()) { "invalid Boost config ledger" } }
            }.toMutableMap()
        }
        fun writeLedger(key: String, values: Map<String, Double>) {
            pending[key] = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()
        }
        if (store.getInt(REDRIVE_SCHEMA, 0) < Derive.REDRIVE_SCHEMA_VERSION) {
            pending[LAST_REDRIVE] = 0L
            pending[REDRIVE_SCHEMA] = Derive.REDRIVE_SCHEMA_VERSION
        }
        if (flag(BoostApsSettingKeys.ApsBoostV5AutoConfigDone.key)) {
            Apply.migrateLegacyDoneFlag(Apply.managedDoubleKeys, { number(it.key) }, { mark(it.key) })
            val fc = booleanKeys.first()
            if (store.contains(fc.key) && flag(fc.key) != fc.defaultValue) mark(fc.key)
            pending[BoostApsSettingKeys.ApsBoostV5AutoConfigDone.key] = false
        }
        Apply.runSchemaMigrations(store.getInt(SCHEMA, 0), Apply.managedDoubleKeys,
            { done(it.key) }, { number(it.key) }, { pending[resolved(it.key)] = null }, { pending[SCHEMA] = it })
        var applied = 0
        var held = 0
        var redriven = 0
        val allKeys = Apply.managedDoubleKeys.map { it.key } + booleanKeys.map { it.key }
        if (!allKeys.all(::done)) {
            val profile = gather(now, Derive.LOOKBACK_DAYS, maxIob, maxBolus)
            Derive.compute(profile)?.let { suggestion ->
                val res = Apply.applyAutoConfig(suggestion, profile.tbrBelow70Pct, profile.timeBelow54Pct,
                    { done(it.key) }, { number(it.key) }, { k, v -> pending[k.key] = v }, { mark(it.key) })
                applied += res.count { it.outcome == Apply.Outcome.APPLIED }
                held += res.count { it.outcome == Apply.Outcome.SUGGESTED_NOT_APPLIED_TBR }
                val values = listOf(suggestion.fastCarbConfirm, suggestion.aggressiveEarlyConfirm,
                    suggestion.velocityBudgetFloor, suggestion.primerTbrFallback)
                booleanKeys.zip(values).forEach { (k, v) ->
                    if (!done(k.key)) {
                        if (!store.contains(k.key) || flag(k.key) == k.defaultValue) {
                            pending[k.key] = v
                            if (v != k.defaultValue) applied++
                        }
                        mark(k.key)
                    }
                }
            }
        }
        // Match source cadence and prerequisite set (the four newer booleans still onboard above).
        val redriveKeys = Apply.managedDoubleKeys.map { it.key } + booleanKeys.first().key
        val last = (pending[LAST_REDRIVE] as? Long) ?: store.getLong(LAST_REDRIVE, 0L)
        if (last == 0L || now - last >= Derive.REDRIVE_INTERVAL_DAYS * 86_400_000L) {
            pending[LAST_REDRIVE] = now
            val prefix = "@${iso(now)},win=${Derive.REDRIVE_LOOKBACK_DAYS}d"
            val missing = redriveKeys.filterNot(::done)
            if (missing.isNotEmpty()) {
                pending[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key] = "$prefix,skip=ONBOARDING_INCOMPLETE,${missing.joinToString("|")}"
            } else {
                val profile = gather(now, Derive.REDRIVE_LOOKBACK_DAYS, maxIob, maxBolus)
                val suggestion = Derive.compute(profile)
                if (suggestion == null) {
                    pending[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key] =
                        "$prefix,skip=INSUFFICIENT_HISTORY,days=${profile.daysWithData},bg=${profile.bgReadingCount}"
                } else {
                    val baseline = ledger(BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key)
                    val awaiting = ledger(BoostApsSettingKeys.ApsBoostV5AutoConfigPending.key)
                    val operativeBefore = Apply.managedDoubleKeys.associateWith { number(it.key) ?: it.defaultValue }
                    val res = Apply.redrive(suggestion, profile.tbrBelow70Pct, profile.timeBelow54Pct,
                        { number(it.key) }, { baseline[it.key] }, { awaiting[it.key] },
                        { k, v -> pending[k.key] = v }, { k, v -> baseline[k.key] = v },
                        { k, v -> if (v == null) awaiting.remove(k.key) else awaiting[k.key] = v })
                    writeLedger(BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key, baseline)
                    writeLedger(BoostApsSettingKeys.ApsBoostV5AutoConfigPending.key, awaiting)
                    val changed = res.filter { it.outcome == Apply.Outcome.REDRIVEN }
                    val blocked = res.filter { it.outcome == Apply.Outcome.SUGGESTED_NOT_APPLIED_TBR }
                    val retired = res.filter { it.outcome == Apply.Outcome.RETIRED_USER_EDITED }
                    redriven = changed.size
                    held += blocked.size
                    fun short(k: Knob) = k.name.removePrefix("ApsBoostV5").removePrefix("ApsBoost")
                    pending[BoostApsSettingKeys.ApsBoostV5RedriveSummary.key] = "$prefix,ev=${res.size},ch=${changed.size}" +
                        changed.joinToString("") { ",${short(it.key)}:${it.operativeValue}" } +
                        blocked.joinToString("") { ",held-${short(it.key)}:${it.suggestedValue}" } +
                        retired.joinToString("") { ",retired-${short(it.key)}" }
                    if (changed.isNotEmpty() || blocked.isNotEmpty()) {
                        val line = changed.joinToString("; ") { "${short(it.key)} ${it.suggestedValue} (was ${operativeBefore.getValue(it.key)})" }
                        val entry = "${iso(now)} ${line.ifEmpty { "held only" }}"
                        pending[BoostApsSettingKeys.ApsBoostV5RedriveHistory.key] =
                            (listOf(entry) + text(BoostApsSettingKeys.ApsBoostV5RedriveHistory.key).split("\n")
                                .filter { it.isNotBlank() }).take(10).joinToString("\n")
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (pending.isNotEmpty()) {
            val watched = pending.keys + Apply.managedDoubleKeys.map { it.key } + booleanKeys.map { it.key } +
                listOf(BoostApsSettingKeys.ApsBoostV5RedriveBaseline.key, BoostApsSettingKeys.ApsBoostV5AutoConfigPending.key)
            val current = store.getAll()
            check(watched.all { original[it] == current[it] }) { "Boost config settings changed while reading history" }
        }
        if (pending.isNotEmpty()) store.edit(commit = true) {
            pending.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is Boolean -> putBoolean(key, value)
                is Double -> putDouble(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                else -> error("invalid Boost config write")
            } }
        }
        return BoostAutoConfigNotice(applied, redriven, held).takeIf { applied + redriven + held > 0 }
    }

    private suspend fun gather(now: Long, days: Long, maxIob: Double, maxBolus: Double): Derive.V1Profile {
        val since = now - days * 86_400_000L
        val tdds = tdd.calculate(now, days, allowMissingDays = true)
        val amounts = buildList {
            if (tdds != null) for (i in 0 until tdds.size()) {
                val row = tdds.valueAt(i)
                val amount = if (row.totalAmount > 0) row.totalAmount else row.basalAmount + row.bolusAmount
                require(amount.isFinite()) { "invalid Boost auto-config TDD" }
                if (amount > 0) add(amount)
            }
        }
        val boluses = persistence.getBolusesFromTimeToTime(since, now, true)
        val bgs = persistence.getBgReadingsDataFromTimeToTime(since, now, true)
        require(bgs.all { it.value.isFinite() } && boluses.all { it.amount.isFinite() }) { "invalid Boost auto-config history" }
        val n = bgs.size
        return Derive.V1Profile(amounts.size, n, Derive.percentile(amounts, 50.0),
            boluses.filter { it.type == BS.Type.NORMAL && it.amount > 0 }.map { it.amount },
            boluses.filter { it.type == BS.Type.SMB && it.amount > 0 }.map { it.amount },
            if (n > 0) 100.0 * bgs.count { it.value >= 1 && it.value < 70 } / n else 0.0,
            if (n > 0) 100.0 * bgs.count { it.value >= 1 && it.value < 54 } / n else 0.0,
            if (n > 0) bgs.sumOf { it.value } / n else 0.0, maxIob, maxBolus)
    }
}

/** Presentation counts; canonical source policy reasons remain in the persisted ledgers. */
data class BoostAutoConfigNotice(val applied: Int, val redriven: Int, val held: Int)
