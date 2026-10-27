package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.plugins.aps.openAPSBoost.trancheReleaseCeiling
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The property that has to hold whatever the rule decides is that this never delivers more than the
 * engine would have delivered without it. Everything else is a question of when.
 */
class ConfirmTrancheControllerTest {

    private val min = 60_000L

    @Test fun `the immediate part is the configured fraction and the rest is held`() {
        val c = ConfirmTrancheController(immediateFraction = 0.5)
        assertThat(c.onConfirm(0L, 120.0, 2.0)).isWithin(1e-9).of(1.0)
        assertThat(c.heldU()).isWithin(1e-9).of(1.0)
    }

    @Test fun `nothing is released before the hold window`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0)
        c.onConfirm(0L, 120.0, 2.0)
        assertThat(c.onCycle(5 * min, 160.0)).isEqualTo(0.0)
        assertThat(c.heldU()).isWithin(1e-9).of(1.0)
    }

    @Test fun `a continuing rise releases the remainder`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0, releaseThreshold = 0.48)
        c.onConfirm(0L, 110.0, 2.0)
        c.onCycle(5 * min, 130.0)
        assertThat(c.onCycle(10 * min, 160.0)).isWithin(1e-9).of(1.0)
        assertThat(c.heldU()).isEqualTo(0.0)
    }

    @Test fun `a rise that goes nowhere keeps the remainder`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0, releaseThreshold = 0.48)
        c.onConfirm(0L, 180.0, 2.0)
        c.onCycle(5 * min, 181.0)
        assertThat(c.onCycle(10 * min, 180.0)).isEqualTo(0.0)
        assertThat(c.heldU()).isEqualTo(0.0)   // decided, not carried
    }

    @Test fun `a hold that outlives its window is dropped rather than carried`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0, expiryMinutes = 30.0)
        c.onConfirm(0L, 110.0, 2.0)
        assertThat(c.onCycle(40 * min, 250.0)).isEqualTo(0.0)
        assertThat(c.heldU()).isEqualTo(0.0)
    }

    @Test fun `total delivered never exceeds the sized dose`() {
        for (thr in listOf(0.0, 0.3, 0.48, 0.9)) {
            for (bgEnd in listOf(90.0, 140.0, 200.0, 300.0)) {
                val c = ConfirmTrancheController(releaseThreshold = thr)
                val now = c.onConfirm(0L, 120.0, 2.0)
                c.onCycle(5 * min, (120.0 + bgEnd) / 2)
                val later = c.onCycle(10 * min, bgEnd)
                assertThat(now + later).isAtMost(2.0 + 1e-9)
            }
        }
    }

    @Test fun `a fraction of one delivers everything immediately and holds nothing`() {
        val c = ConfirmTrancheController(immediateFraction = 1.0)
        assertThat(c.onConfirm(0L, 120.0, 2.0)).isWithin(1e-9).of(2.0)
        assertThat(c.heldU()).isEqualTo(0.0)
        assertThat(c.onCycle(10 * min, 200.0)).isEqualTo(0.0)
    }

    @Test fun `a fresh confirm replaces an older hold`() {
        val c = ConfirmTrancheController()
        c.onConfirm(0L, 120.0, 2.0)
        c.onConfirm(15 * min, 150.0, 3.0)
        assertThat(c.heldU()).isWithin(1e-9).of(1.5)
    }

    @Test fun `reset drops the hold`() {
        val c = ConfirmTrancheController()
        c.onConfirm(0L, 120.0, 2.0)
        c.reset()
        assertThat(c.heldU()).isEqualTo(0.0)
        assertThat(c.onCycle(10 * min, 200.0)).isEqualTo(0.0)
    }

    @Test fun `a cycle landing just short of the window still decides`() {
        // 2026-08-27: the confirm at 14:52:11.459 was followed by a cycle at 15:02:10.934, which is
        // 9.991 minutes. An exact comparison deferred the decision by a whole cycle.
        val c = ConfirmTrancheController(holdMinutes = 10.0, releaseThreshold = 0.48)
        c.onConfirm(0L, 180.0, 2.0)
        c.onCycle(5 * min, 181.0)
        val short = (9.991 * 60_000).toLong()
        assertThat(c.onCycle(short, 180.0)).isEqualTo(0.0)
        assertThat(c.heldU()).isEqualTo(0.0)   // decided at 9.991, not deferred to 15 min
    }

    @Test fun `a cycle far short of the window still defers`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0)
        c.onConfirm(0L, 120.0, 2.0)
        assertThat(c.onCycle(6 * min, 160.0)).isEqualTo(0.0)
        assertThat(c.heldU()).isWithin(1e-9).of(1.0)   // still held
    }
    @Test fun `safety bounds discard held dose when meal state ends`() {
        val c = ConfirmTrancheController()
        c.onConfirm(0L, 110.0, 2.0)
        val result = c.onCycleBounded(10 * min, 160.0, ConfirmTrancheController.ReleaseBounds(false, false, false, 10.0))
        assertThat(result.units).isEqualTo(0.0)
        assertThat(c.heldU()).isEqualTo(0.0)
    }

    @Test fun `safety bounds discard held dose on rescue and hard gate`() {
        for (rescue in listOf(true, false)) {
            val c = ConfirmTrancheController()
            c.onConfirm(0L, 110.0, 2.0)
            val result = c.onCycleBounded(10 * min, 160.0, ConfirmTrancheController.ReleaseBounds(true, !rescue, rescue, 10.0))
            assertThat(result.units).isEqualTo(0.0)
            assertThat(c.heldU()).isEqualTo(0.0)
        }
    }


    private fun safeBounds(ceiling: Double = 10.0) = ConfirmTrancheController.ReleaseBounds(
        inMealState = true, hardGateFired = false, postRescueWindow = false, ceilingU = ceiling
    )

    private fun armedForBoundedRelease(): ConfirmTrancheController =
        ConfirmTrancheController(holdMinutes = 10.0, releaseThreshold = 0.48).also {
            it.onConfirm(0L, 110.0, 2.0)
            it.onCycleBounded(5 * min, 130.0, safeBounds())
        }

    @Test fun `bounded tranche respects available headroom`() {
        val release = armedForBoundedRelease().onCycleBounded(10 * min, 160.0, safeBounds(0.4))
        assertThat(release.units).isWithin(1e-9).of(0.4)
        assertThat(release.note).isEqualTo("clamped:1.0->0.4")
    }

    @Test fun `bounded tranche releases whole hold when all guards are open`() {
        val release = armedForBoundedRelease().onCycleBounded(10 * min, 160.0, safeBounds())
        assertThat(release.units).isWithin(1e-9).of(1.0)
        assertThat(release.note).isEmpty()
    }

    @Test fun `bounded tranche never exceeds original confirm shot`() {
        val c = ConfirmTrancheController(holdMinutes = 10.0, releaseThreshold = 0.0)
        var total = c.onConfirm(0L, 110.0, 2.0)
        for (step in 1..8) {
            total += c.onCycleBounded(step * 5 * min, 110.0 + 20 * step, safeBounds()).units
        }
        assertThat(total).isAtMost(2.0 + 1e-9)
    }


    @Test fun `release ceiling respects max IOB and per cycle confirm cap`() {
        val ceiling = ::trancheReleaseCeiling
        assertThat(ceiling(0.5, 3.0, 1.8, 2.0)).isWithin(1e-9).of(0.7)
        assertThat(ceiling(0.5, 6.0, 0.0, 1.0)).isWithin(1e-9).of(0.5)
        assertThat(ceiling(0.5, 2.0, 2.2, 2.0)).isEqualTo(0.0)
    }

    @Test fun `invalid release headroom fails closed`() {
        val ceiling = ::trancheReleaseCeiling
        assertThat(ceiling(0.5, Double.NaN, 0.0, 2.0)).isEqualTo(0.0)
        assertThat(ceiling(0.5, 3.0, Double.POSITIVE_INFINITY, 2.0)).isEqualTo(0.0)
    }
}
