package app.aaps.plugins.boostport

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.aps.openAPSBoostV5.*
import app.aaps.plugins.boostport.reference.ReferenceDetermineBasalBoost
import app.aaps.plugins.boostport.reference.ReferenceV5StateStore
import app.aaps.plugins.boostport.telemetry.BoostMlAssetParser
import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit.Kind
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BoostReplayPersistenceTest {
    /** Exercise the real storage boundary, including stale reads and write failures. */
    private class Storage(private val file: Path? = null) {
        var visible = ""
        var written = ""
        var writes = 0
        var failRead = false
        var failWrite = false
        var delayed = false
        val keys = mutableListOf<String>()
        val backend = Proxy.newProxyInstance(KeyValueStore::class.java.classLoader, arrayOf(KeyValueStore::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> {
                    if (failRead) error("read unavailable")
                    keys.add(args[0] as String)
                    if (file != null && Files.exists(file)) Files.readString(file) else visible
                }
                "putString" -> {
                    if (failWrite) error("write unavailable")
                    keys.add(args[0] as String)
                    written = args[1] as String
                    writes++
                    if (file != null) Files.writeString(file, written)
                    if (!delayed) visible = written
                    null
                }
                else -> error("Unexpected storage call: ${method.name}")
            }
        } as KeyValueStore
    }

    private fun state() = V5PersistedState(
        mealHypothesis = MealHypothesisState(state = MealHypothesis.COMMITTED, ageCycles = 3,
            maxScoreInObserving = 0.8, maxEventualBgOffsetInObserving = 90.0, committedInSession = true,
            lastAgeMs = V1ReplayFixture.now, lastCommitMs = V1ReplayFixture.now - 300_000L,
            nonPositiveRunStartMs = 1000L, maxScoreAtMs = 2000L, maxOffsetAtMs = 3000L),
        mlMealLikelyNullStreak = 2, lastCycleScore = 0.9, scoreReadySinceMs = 4000L,
        mlNullStreakLastMs = 5000L, primerAppliedU = 0.2, primerNettingResidualU = -0.05,
        primerIobU = 0.1, primerIobUpdatedMs = V1ReplayFixture.now,
    )
    private fun history() = listOf(V1ReplayFeatures.CycleSnapshot(V1ReplayFixture.now, 150.0, 0.5, 0.01, 170.0, 0.2, 3.0))
    private fun snapshot() = BoostReplayStateCodec.Snapshot(history(), state())
    private fun persisted(s: V5PersistedState) = s.copy(lastCycleScore = null, scoreReadySinceMs = 0, mlNullStreakLastMs = 0)
    private fun loaded(store: BoostReplayStateStore) = assertIs<BoostReplayStateStore.Load.Ready>(store.load()).snapshot

    @Test fun v5PayloadAndRestartRestoreMatchTheOriginalStore() {
        var sourceJson = ""
        val source = ReferenceV5StateStore({ sourceJson }, { sourceJson = it })
        source.save(state())
        assertEquals(state(), source.load(), "source also retains transient fields in cache")
        val encoded = assertNotNull(BoostReplayStateCodec.encode(snapshot()))
        val actualJson = JSONObject(encoded).getJSONObject("v5State")
        val expectedJson = JSONObject(sourceJson)
        assertEquals(expectedJson.keys().asSequence().toSet(), actualJson.keys().asSequence().toSet())
        for (key in expectedJson.keys().asSequence().map { it as String }) {
            when (expectedJson.get(key)) {
                is Number -> assertEquals(expectedJson.getDouble(key), actualJson.getDouble(key), key)
                else -> assertEquals(expectedJson.get(key), actualJson.get(key), key)
            }
        }
        val sourceRestored = ReferenceV5StateStore({ sourceJson }, { sourceJson = it }).load()
        val actual = assertNotNull(BoostReplayStateCodec.decode(encoded))
        assertEquals(sourceRestored, actual.v5)
        assertEquals(persisted(state()), actual.v5)
        assertEquals(history(), actual.history)
    }

    @Test fun legacyFeatureJsonMatchesOriginalBuilderAndStateDefaults() {
        val oldEngine = ReferenceDetermineBasalBoost(V1ReplayFixture.presentation, V1ReplayFixture.clock)
        val row = history().single()
        val raw = """[{"ts":${row.ts},"cgm_mgdl":150,"iob_iob":0.5,"iob_activity":0.01,"sug_eventualBG":170,"recent_smb_units_60m":0.2,"sug_minDelta":3}]"""
        oldEngine.loadMlRingBufferOnce(raw)
        val legacy = assertNotNull(BoostReplayStateCodec.fromLegacy(oldEngine.serializeMlRingBuffer(), """{"mealHypothesis":"OBSERVING","mealHypothesisAge":2}"""))
        assertEquals(history(), legacy.history)
        assertEquals(MealHypothesis.OBSERVING, legacy.v5.mealHypothesis.state)
        assertEquals(2, legacy.v5.mealHypothesis.ageCycles)
        assertFalse(legacy.v5.mealHypothesis.committedInSession)
        assertEquals(BoostReplayStateCodec.Snapshot(), BoostReplayStateCodec.fromLegacy("", ""))
    }

    @Test fun cacheRetainsLockAndTransientScoreDespiteDelayedBackingReads() {
        val disk = Storage().also { it.delayed = true }
        val store = BoostReplayStateStore(disk.backend)
        assertEquals(BoostReplayStateCodec.Snapshot(), loaded(store))
        assertTrue(store.save(snapshot()).persisted)
        assertEquals("", disk.visible, "write not visible yet")
        repeat(10) { assertEquals(snapshot(), loaded(store)) }
        disk.visible = disk.written
        val afterRestart = loaded(BoostReplayStateStore(disk.backend))
        assertEquals(persisted(state()), afterRestart.v5)
        assertTrue(afterRestart.v5.mealHypothesis.committedInSession)
        assertEquals(setOf(BoostReplayStateKey.Session.key), disk.keys.toSet())
    }

    @Test fun writeFailureIsExplicitAndNeverRollsBackTheInProcessState() {
        val disk = Storage().also { it.failWrite = true }
        val store = BoostReplayStateStore(disk.backend)
        val failed = store.save(snapshot())
        assertFalse(failed.persisted)
        assertEquals("state-write-failed", failed.problem)
        assertEquals(snapshot(), loaded(store))
        assertEquals(0, disk.writes)
        disk.failWrite = false
        assertTrue(store.save(loaded(store)).persisted)
        assertEquals(persisted(state()), loaded(BoostReplayStateStore(disk.backend)).v5)
    }

    @Test fun corruptUnknownOrMistypedStateIsNotReplacedWithAnUnlockedSession() {
        val raw = assertNotNull(BoostReplayStateCodec.encode(snapshot()))
        for (bad in listOf("{", "[]", " ".repeat(32_001), "[".repeat(20) + "0" + "]".repeat(20),
            raw.replace("\"schema\":1", "\"schema\":2"),
            raw.replace("\"committedInSession\":true", "\"committedInSession\":\"true\""),
            raw.replace("\"mealHypothesisAge\":3", "\"mealHypothesisAge\":-1"))) {
            val disk = Storage().also { it.visible = bad }
            val rejected = assertIs<BoostReplayStateStore.Load.Rejected>(BoostReplayStateStore(disk.backend).load())
            assertEquals("invalid-replay-state", rejected.reason)
            assertEquals(bad, disk.visible)
            assertEquals(0, disk.writes)
        }
        assertNull(BoostReplayStateCodec.fromLegacy("[]", """{"mealHypothesis":"IDLE","mealHypothesisAge":"0"}"""))
    }

    @Test fun readFailureOrCorruptionStopsBeforeAnyEngineCalculationOrWrite() {
        var calculations = 0
        val runner = BoostV1V5Replay(V1ReplayFixture.presentation, V1ReplayFixture.clock,
            decideV5 = { _, _ -> calculations++; error("must not calculate") })
        for (disk in listOf(Storage().also { it.failRead = true }, Storage().also { it.visible = "{bad}" })) {
            val result = BoostReplaySession(runner, BoostReplayStateStore(disk.backend)).run(
                V1ReplayFixture.recorded(V1ReplayFixture.cycle()), V5ReplayContext(0.05, true))
            assertIs<BoostReplaySession.Result.Rejected>(result)
            assertEquals(0, disk.writes)
        }
        assertEquals(0, calculations)
    }

    private fun runner(): BoostV1V5Replay {
        fun parsed(kind: Kind, path: String) = assertIs<BoostMlAssetParser.Result.Ready>(BoostMlAssetParser.parse(kind,
            checkNotNull(javaClass.classLoader.getResourceAsStream(path)).bufferedReader().use { it.readText() })).model
        return BoostV1V5Replay(V1ReplayFixture.presentation, V1ReplayFixture.clock,
            assertNotNull(V1ReplayRiskTreeModel.from(parsed(Kind.HYPO_RISK_V12, "boost/hypo_risk_model.json"))),
            assertNotNull(V1ReplayMealTreeModel.from(parsed(Kind.MEAL_LIKELIHOOD, "boost/meal_likelihood_model.json"))))
    }

    @Test fun fullSessionMatchesExplicitSourceStateAcrossRealFileRestart() {
        val dir = Files.createTempDirectory("boost-replay-state-")
        val path = dir.resolve("state.json")
        try {
            var disk = Storage(path)
            var session = BoostReplaySession(runner(), BoostReplayStateStore(disk.backend))
            var direct = runner()
            var prior = V5PersistedState()
            for (n in 0 until 20) {
                if (n == 10) {
                    val decoded = assertNotNull(BoostReplayStateCodec.decode(Files.readString(path)))
                    assertTrue(decoded.v5.mealHypothesis.committedInSession)
                    prior = persisted(prior)
                    direct = runner().also { it.restoreFeatureHistoryOnce(decoded.history) }
                    disk = Storage(path)
                    session = BoostReplaySession(runner(), BoostReplayStateStore(disk.backend))
                }
                val c = V1ReplayFixture.cycle(170.0 + n * 3, 8.0, 6.0, 3.0, 0.5)
                val time = c.time + n * 300_000L
                val cycle = V1ReplayFixture.recorded(c.copy(time = time, glucose = c.glucose.copy(date = time)))
                val context = V5ReplayContext(0.05, true, manualBolusInAnnouncementWindow = n in 2..4)
                val expected = direct.run(cycle, context, prior)
                val actual = assertIs<BoostReplaySession.Result.Calculated>(session.run(cycle, context))
                assertTrue(actual.persistence.persisted)
                assertEquals(expected.v5, actual.frame.v5)
                assertEquals(expected.nextV5State, actual.frame.nextV5State)
                assertEquals(expected.v1.copy(reason = actual.frame.v1.reason), actual.frame.v1)
                assertEquals(expected.finalCandidate.copy(reason = actual.frame.finalCandidate.reason), actual.frame.finalCandidate)
                assertEquals(expected.finalCandidate.reason.toString(), actual.frame.finalCandidate.reason.toString())
                prior = expected.nextV5State
            }
            val final = assertNotNull(BoostReplayStateCodec.decode(Files.readString(path)))
            assertTrue(final.v5.mealHypothesis.committedInSession)
            assertTrue(final.history.isNotEmpty())
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(dir) }
    }
}
