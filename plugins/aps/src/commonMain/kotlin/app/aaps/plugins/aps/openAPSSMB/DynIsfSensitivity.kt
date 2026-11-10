package app.aaps.plugins.aps.openAPSSMB

import kotlin.math.ln

/**
 * The DynamicISF sensitivity model, ported unchanged from AAPS 3.x / upstream 4.0:
 *
 * `sens(bg) = 1800 / (TDD * ln(bg / insulinDivisor + 1))`
 *
 * The 4.0 codebase needs the value at more than one BG in a single run (current BG, the BG a
 * prediction tick is at, the BG the dose is computed at), so the formula is expressed as
 * [baseFromTdd] (the value at the normal target) times a BG-dependent factor in [atBg]. The
 * algebra is identical: `baseFromTdd(tdd, d) * (ln(100 / d + 1) / ln(bg / d + 1))` equals
 * `1800 / (tdd * ln(bg / d + 1))`, and a test pins that.
 *
 * Do not add a knob here (velocity, caps, level blending). This value decides an insulin dose,
 * it must stay the formula clinicians and users already know.
 */
object DynIsfSensitivity {

    /** BG the base sensitivity is expressed at. Only a point on the same curve, not a target. */
    const val NORMAL_TARGET_MGDL = 100.0

    /** ISF at [NORMAL_TARGET_MGDL] defined by a TDD, without rounding. */
    fun baseFromTdd(tdd: Double, insulinDivisor: Int): Double =
        1800.0 / (tdd * ln((NORMAL_TARGET_MGDL / insulinDivisor) + 1))

    /** ISF at [bg] from an ISF at [NORMAL_TARGET_MGDL]; the 3.x curve, unchanged. */
    fun atBg(bg: Double, baseSensitivity: Double, insulinDivisor: Int): Double {
        val sbgAtNormal = ln((NORMAL_TARGET_MGDL / insulinDivisor) + 1)
        val sbg = ln((bg / insulinDivisor) + 1)
        return baseSensitivity * (sbgAtNormal / sbg)
    }
}
