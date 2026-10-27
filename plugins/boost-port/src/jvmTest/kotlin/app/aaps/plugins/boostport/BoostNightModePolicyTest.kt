package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector.SleepState
import app.aaps.plugins.boostport.aps.BoostNightModePolicy
import app.aaps.plugins.boostport.aps.BoostApsRuntimeSettings
import app.aaps.plugins.boostport.reference.night.ReferenceBoostNightMode
import kotlin.test.*

class BoostNightModePolicyTest {
    private val day = 20_000L * 86_400_000L
    private val settings = BoostNightModePolicy.Settings(true, 22 * 3_600_000L, 7 * 3_600_000L,
        false, false, false, 27.0)
    private fun context(minute: Int = 22 * 60) = BoostNightModePolicy.Context(
        day + minute * 60_000L, day, 126.0, 100.0, null, 0.0, SleepState.AWAKE
    )

    @Test fun decisionMatchesOriginalMethodAcrossClockSleepMealAndTargetContexts() {
        var compared = 0
        for ((start, end) in listOf(1320 to 420, 420 to 1320, 720 to 720))
            for (minute in listOf(0, 419, 420, 719, 720, 1319, 1320, 1439))
                for (flags in 0..15) for (sleep in SleepState.entries)
                    for (cob in listOf(0.0, 20.0)) for (target in listOf(null, 90.0, 100.0, 140.0))
                        for (bg in listOf(126.99, 127.0, 127.01)) {
                            val s = settings.copy(enabled = flags and 1 != 0,
                                autoBySleep = flags and 2 != 0, disableWithCob = flags and 4 != 0,
                                disableWithLowTempTarget = flags and 8 != 0,
                                startOfDayMs = start * 60_000L, endOfDayMs = end * 60_000L)
                            val c = context(minute).copy(sleepState = sleep, mealCob = cob,
                                temporaryTargetMgDl = target, glucoseMgDl = bg)
                            assertEquals(ReferenceBoostNightMode(s, c).isActive(),
                                BoostNightModePolicy.isActive(s, c), "$s $c")
                            compared++
                        }
        assertEquals(27_648, compared)
    }

    @Test fun wrappedAndEmptyWindowsKeepSourceBoundaries() {
        assertTrue(BoostNightModePolicy.isActive(settings, context(1320)))
        assertTrue(BoostNightModePolicy.isActive(settings, context(419)))
        assertFalse(BoostNightModePolicy.isActive(settings, context(420)))
        assertFalse(BoostNightModePolicy.isActive(settings, context(1319)))
        assertFalse(BoostNightModePolicy.isActive(settings.copy(endOfDayMs = settings.startOfDayMs), context()))
    }

    @Test fun sleepCanExtendOrStartTheWindowButStillHonorsMealAndLowTargetExceptions() {
        val s = settings.copy(autoBySleep = true)
        val c = context(720).copy(sleepState = SleepState.PRE_SLEEP)
        assertTrue(BoostNightModePolicy.isActive(s, c))
        assertTrue(BoostNightModePolicy.isActive(s, c.copy(sleepState = SleepState.SLEEPING)))
        assertFalse(BoostNightModePolicy.isActive(s.copy(disableWithCob = true), c.copy(mealCob = 1.0)))
        assertFalse(BoostNightModePolicy.isActive(s.copy(disableWithLowTempTarget = true), c.copy(temporaryTargetMgDl = 90.0)))
    }

    @Test fun glucoseLimitIsStrictAndUsesOriginalProfileTargetNotTemporaryTarget() {
        assertTrue(BoostNightModePolicy.isActive(settings, context().copy(glucoseMgDl = 126.99)))
        assertFalse(BoostNightModePolicy.isActive(settings, context().copy(glucoseMgDl = 127.0)))
        assertFalse(BoostNightModePolicy.isActive(settings, context().copy(glucoseMgDl = 130.0, temporaryTargetMgDl = 140.0)))
        assertTrue(BoostNightModePolicy.isActive(settings.copy(disableWithLowTempTarget = true),
            context().copy(temporaryTargetMgDl = 100.0)))
    }

    @Test fun standaloneMissingGlucoseOrProfileMatchesSourceShortCircuits() {
        for (c in listOf(context().copy(glucoseMgDl = null), context().copy(profileTargetMgDl = null))) {
            assertFalse(ReferenceBoostNightMode(settings, c).isActive())
            assertFalse(BoostNightModePolicy.isActive(settings, c))
        }
        assertFalse(BoostNightModePolicy.isActive(settings.copy(enabled = false, bgOffsetMgDl = Double.NaN), context()))
    }

    @Test fun lowTemporaryTargetUsesSourceHardLimitClamping() {
        val s = settings.copy(disableWithLowTempTarget = true)
        for (target in listOf(30.0, 72.0, 200.0, 400.0)) {
            val c = context().copy(profileTargetMgDl = 250.0, temporaryTargetMgDl = target)
            assertEquals(ReferenceBoostNightMode(s, c).isActive(), BoostNightModePolicy.isActive(s, c))
        }
    }

    @Test fun sourceClockParsingRetainsMidnightFallbackAndSmartTwentyFourHourTime() {
        val reference = ReferenceBoostNightMode(settings, context())
        for (raw in listOf("22:00", "7:00", "07:00", "24:00", "bad", "", "25:00", "7:1", " 7:00")) {
            assertEquals(reference.parseTimeToMillis(raw), BoostApsRuntimeSettings.clock(raw), raw)
        }
    }
}
