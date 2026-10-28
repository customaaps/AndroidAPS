package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfig as Target
import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfigApply as Apply
import app.aaps.plugins.boostport.reference.autoconfig.BoostV5AutoConfig as Source
import app.aaps.plugins.boostport.reference.autoconfig.BoostV5AutoConfigApply as Reference
import kotlin.test.*

class BoostAutoConfigSourceParityTest {
    private fun source(tdd: Double, low70: Double, severe: Double, samples: Int) = Source.V1Profile(
        14, 4000, tdd, List(samples) { 1.0 + it * 0.1 }, listOf(0.1, 0.2, 0.3, 0.5), low70, severe, 130.0, 3.0, 5.0)
    private fun target(tdd: Double, low70: Double, severe: Double, samples: Int) = Target.V1Profile(
        14, 4000, tdd, List(samples) { 1.0 + it * 0.1 }, listOf(0.1, 0.2, 0.3, 0.5), low70, severe, 130.0, 3.0, 5.0)

    @Test fun derivationAndOnboardingMatchFrozenSourceIncludingUserTuningAndHypoGuards() {
        var compared = 0
        for (tdd in listOf(12.0, 20.0, 45.0, 100.0))
            for (low70 in listOf(0.0, 1.49, 1.5, 4.0, 4.01, 6.1))
                for (severe in listOf(0.0, 0.3, 0.99, 1.0, 1.51))
                    for (samples in listOf(0, 4, 10, 20)) {
                        val s = assertNotNull(Source.compute(source(tdd, low70, severe, samples)))
                        val t = assertNotNull(Target.compute(target(tdd, low70, severe, samples)))
                        // The port intentionally adapts the human-readable primer rationale.
                        assertEquals(s.copy(rationale = emptyList()).toString(), t.copy(rationale = emptyList()).toString())
                        for (mode in 0..2) {
                            val expected = mutableMapOf<String, Double>()
                            val actual = mutableMapOf<String, Double>()
                            if (mode > 0) {
                                val initial = if (mode == 1) 0.25 else 1.8 // historical factory vs tuned
                                expected["boost_v5_committed_cap_u"] = initial
                                actual.putAll(expected)
                            }
                            val sr = mutableSetOf<String>(); val tr = mutableSetOf<String>()
                            val a = Reference.applyAutoConfig(s, low70, severe, { it.key in sr }, { expected[it.key] },
                                { k, v -> expected[k.key] = v }, { sr.add(it.key) })
                            val b = Apply.applyAutoConfig(t, low70, severe, { it.key in tr }, { actual[it.key] },
                                { k, v -> actual[k.key] = v }, { tr.add(it.key) })
                            assertEquals(a.toString(), b.toString())
                            assertEquals(expected, actual)
                            assertEquals(sr, tr)
                            compared++
                        }
                    }
        assertEquals(1440, compared)
    }

    @Test fun repeatedRedriveMatchesFrozenSourceAcrossBaselinePendingClippingAndRaiseGuardStates() {
        var compared = 0
        for (low70 in listOf(0.0, 4.0, 4.01)) for (severe in listOf(0.0, 0.99, 1.0))
            for (tdd in listOf(12.0, 30.0, 80.0)) for (mode in 0..2) {
                val s = assertNotNull(Source.compute(source(tdd, low70, severe, 10)))
                val t = assertNotNull(Target.compute(target(tdd, low70, severe, 10)))
                val expected = Reference.managedDoubleKeys.associate { it.key to it.defaultValue }.toMutableMap()
                val actual = expected.toMutableMap()
                val sb = mutableMapOf<String, Double>(); val tb = mutableMapOf<String, Double>()
                if (mode > 0) Reference.REDRIVE_KEYS.forEach { sb[it.key] = if (mode == 1) 0.5 else 1.0 }
                tb.putAll(sb)
                val sp = mutableMapOf<String, Double>(); val tp = mutableMapOf<String, Double>()
                repeat(4) {
                    val a = Reference.redrive(s, low70, severe, { expected[it.key] }, { sb[it.key] }, { sp[it.key] },
                        { k, v -> expected[k.key] = v }, { k, v -> sb[k.key] = v },
                        { k, v -> if (v == null) sp.remove(k.key) else sp[k.key] = v })
                    val b = Apply.redrive(t, low70, severe, { actual[it.key] }, { tb[it.key] }, { tp[it.key] },
                        { k, v -> actual[k.key] = v }, { k, v -> tb[k.key] = v },
                        { k, v -> if (v == null) tp.remove(k.key) else tp[k.key] = v })
                    assertEquals(a.toString(), b.toString())
                    assertEquals(expected, actual); assertEquals(sb, tb); assertEquals(sp, tp)
                    compared++
                }
            }
        assertEquals(324, compared)
    }
}
