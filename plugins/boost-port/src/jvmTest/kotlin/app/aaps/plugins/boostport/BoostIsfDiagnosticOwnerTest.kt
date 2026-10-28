package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.data.model.TDD
import app.aaps.plugins.boostport.aps.*
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.boostport.reference.diagnostic.ReferenceVwaTddShadow
import app.aaps.plugins.boostport.reference.diagnostic.ReferenceIsfShadow
import app.aaps.plugins.boostport.telemetry.BoostIsfEmaShadowDiagnostics as Ema
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class BoostIsfDiagnosticOwnerTest {
    private val start = 1_700_100_000_000L
    private inline fun <reified T> proxy(noinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T
    private class Disk {
        val values = mutableMapOf<String, Any>()
        val store = Proxy.newProxyInstance(KeyValueStore::class.java.classLoader, arrayOf(KeyValueStore::class.java)) { _, method, args ->
            when {
                method.name == "getString" -> values[args[0]] ?: args[1]
                method.name == "edit" -> {
                    val pending = values.toMutableMap()
                    val editor = Proxy.newProxyInstance(KeyValueStore.Editor::class.java.classLoader, arrayOf(KeyValueStore.Editor::class.java)) { _, m, a ->
                        check(m.name == "putString"); pending[a[0] as String] = a[1]; null
                    } as KeyValueStore.Editor
                    @Suppress("UNCHECKED_CAST") val block = args[1] as KeyValueStore.Editor.() -> Unit
                    editor.block(); values.clear(); values.putAll(pending); null
                }
                else -> error(method.name)
            }
        } as KeyValueStore
    }
    private fun input(eligible: Boolean = true) = BoostIsfDiagnosticInput(BoostTddParts(40.0, 45.0, 48.0, 8.0, 6.0),
        BoostIsfPreparationResult(50.0, 45.0, 120.0, 55, 0.4, 1.2, 42.0, "", eligible),
        BoostIsfSettings(1.5, 0.7, true, 0.4, 160.0, 100.0, true, true, 160, true, 100, false))
    private fun v1() = V1ReplayResult(runningDynamicIsf = true, units = 0.4, insulinReq = 1.0)
    private fun rt() = RT(runningDynamicIsf = true, units = 0.7, insulinReq = 2.0, rate = 1.2,
        duration = 30, variable_sens = 45.0, carbRatio = 10.0, reason = StringBuilder("candidate; "))
    private fun tdd(body: (Array<out Any?>) -> Double?) = proxy<TddCalculator> { name, args ->
        check(name == "calculateDaily")
        body(args)?.let { TDD(timestamp = start, totalAmount = it) }
    }

    @Test fun liveOwnerPersistsSourceKeysAndNeverChangesTheCandidate() = runBlocking {
        val disk = Disk(); val before = rt(); val result = before.copy(reason = StringBuilder(before.reason))
        val owner = BoostIsfDiagnosticOwner(disk.store, tdd { null })
        owner.complete(result, v1(), input(), start)
        assertEquals(before.units, result.units); assertEquals(before.rate, result.rate)
        assertEquals(before.duration, result.duration); assertEquals(before.insulinReq, result.insulinReq)
        assertEquals(before.variable_sens, result.variable_sens); assertEquals(before.carbRatio, result.carbRatio)
        assertEquals(1.2, result.isfShadow_ratioRaw); assertEquals(1.0, result.isfShadow_ratioEma)
        assertEquals(54.0, result.isfShadow_variableSens)
        assertEquals(0.333, result.isfShadow_microBolus) // V1 0.4 / 1.2, never V6 0.7
        assertEquals(setOf(BoostApsSettingKeys.ApsBoostIsfShadowState.key), disk.values.keys)
        val restored = RT.deserialize(result.serialize())
        assertEquals(result, restored.copy(reason = result.reason))
    }

    @Test fun restoreWarmStateAgreesWithIndependentSourceEmaAcrossRestarts() = runBlocking {
        val disk = Disk(); var sourceState = ""
        for (cycle in 0..40) {
            val now = start + cycle * 21_600_000L
            val result = rt()
            BoostIsfDiagnosticOwner(disk.store, tdd { null }).complete(result, v1(), input(), now)
            val source = ReferenceIsfShadow({ sourceState }, { sourceState = it }).computeShadow(48.0, 40.0, 0.7, 1.5, now)!!
            val target = BoostIsfDiagnosticOwner.decodeEma(disk.values.getValue(BoostApsSettingKeys.ApsBoostIsfShadowState.key) as String)
            assertEquals(source.ema, target.ema, 1e-12)
            assertEquals(source.warmupFraction, result.isfShadow_warmup!!, 0.0051)
        }
    }

    @Test fun sourceVwaHistoryLearningDayRollAndReloadMatchEveryNumericField() = runBlocking {
        var targetState = ""; var sourceState = ""
        var target = BoostVwaTddDiagnostic({ targetState }, { targetState = it })
        var source = ReferenceVwaTddShadow({ sourceState }, { sourceState = it })
        for (cycle in 0..100) {
            val now = start + cycle * 3_600_000L
            target.warmFromHistory(now) { a, b -> (b - a) * 1.5 }
            source.warmFromHistory(now) { a, b -> (b - a) * 1.5 }
            val delivered = (now - target.dayAnchorMs(now)) / 3_600_000.0 * 1.7
            val a = target.compute(now, delivered, 40.0)!!
            val b = source.compute(now, delivered, 40.0)!!
            assertEquals(b.vwaBlend, a.vwaBlend, 1e-12); assertEquals(b.projection, a.projection, 1e-12)
            assertEquals(b.expectedToday, a.expectedToday, 1e-12); assertEquals(b.dayFraction, a.dayFraction, 1e-12)
            assertEquals(b.curveDays, a.curveDays); assertEquals(b.usedPreviousDay, a.usedPreviousDay)
            assertEquals(source.curveSnapshot().toList(), target.curveSnapshot().toList())
            if (cycle % 11 == 0) { target = BoostVwaTddDiagnostic({ targetState }, { targetState = it }); source = ReferenceVwaTddShadow({ sourceState }, { sourceState = it }) }
        }
    }

    @Test fun missingTddAndIneligiblePreparationDoNotReadHistoryOrWriteState() = runBlocking {
        val disk = Disk(); val owner = BoostIsfDiagnosticOwner(disk.store, tdd { error("unexpected history") })
        val result = rt(); owner.complete(result, v1(), null, start); owner.complete(result, v1(), input(false), start)
        assertTrue(disk.values.isEmpty()); assertNull(result.isfShadow_ratioEma); assertNull(result.boostVwa_blend)
    }

    @Test fun failedVwaHistoryLeavesEmaAvailableAndCandidateIntact() = runBlocking {
        val disk = Disk(); val result = rt()
        BoostIsfDiagnosticOwner(disk.store, tdd { error("offline") }).complete(result, v1(), input(), start)
        assertNotNull(result.isfShadow_ratioEma); assertNull(result.boostVwa_blend); assertEquals(0.7, result.units)
    }

    @Test fun cancelledHistoryNeverCommitsEitherCapturedState() = runBlocking {
        val disk = Disk(); val owner = BoostIsfDiagnosticOwner(disk.store, tdd { throw CancellationException("import") })
        assertFailsWith<CancellationException> { owner.complete(rt(), v1(), input(), start) }
        assertTrue(disk.values.isEmpty())
    }

    @Test fun changedStateDuringHistoryReadCannotBeOverwritten() = runBlocking {
        val disk = Disk(); val key = BoostApsSettingKeys.ApsBoostIsfShadowState.key
        val owner = BoostIsfDiagnosticOwner(disk.store, tdd { disk.values[key] = "imported"; null })
        assertFailsWith<IllegalStateException> { owner.complete(rt(), v1(), input(), start) }
        assertEquals(mapOf(key to "imported"), disk.values)
    }

    @Test fun malformedAndFutureDiagnosticStateIsNotPromotedToTelemetry() = runBlocking {
        val disk = Disk(); val ek = BoostApsSettingKeys.ApsBoostIsfShadowState.key; val vk = BoostApsSettingKeys.ApsBoostVwaTddShadowState.key
        disk.values[ek] = """{"emaState":1.3,"lastUpdateMs":${start + 1},"firstSeenMs":$start}"""
        disk.values[vk] = """{"curveDays":-1}"""
        val original = disk.values.toMap(); val result = rt()
        BoostIsfDiagnosticOwner(disk.store, tdd { error("invalid state must not read") }).complete(result, v1(), input(), start)
        assertNull(result.isfShadow_ratioEma); assertNull(result.boostVwa_blend); assertEquals(original, disk.values)
    }

    @Test fun fullOwnerPopulatesVwaAndSerializesAllCounterfactualFields() = runBlocking {
        val disk = Disk(); val result = rt()
        BoostIsfDiagnosticOwner(disk.store, tdd { args ->
            val startH = args[1] as Long; val endH = args[2] as Long
            check(startH != endH) { "Target empty TDD intervals have no samples" }
            (endH - startH) * 1.5
        })
            .complete(result, v1(), input(), start)
        assertNotNull(result.boostVwa_blend); assertEquals(1, result.boostVwa_curveDays)
        assertNotNull(result.boostVwa_expected); assertNotNull(result.boostVwa_usedPrevDay)
        assertEquals(2, disk.values.size)
        val restored = RT.deserialize(result.serialize()); assertEquals(result, restored.copy(reason = result.reason))
    }
}
