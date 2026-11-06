package app.aaps.core.keys.interfaces

import app.aaps.core.keys.step

interface DoublePreferenceKey : PreferenceKey, DoubleNonPreferenceKey {

    /**
     * Default value if not changed from preferences
     */
    override val defaultValue: Double

    /**
     *  Minimal allowed value
     */
    val min: Double

    /**
     *  Maximal allowed value
     */
    val max: Double

    /** Editing increment, independent of display precision. Defaults to the unit's step. */
    val stepSize: Double
        get() = unitType.step()

    /**
     *  Value with calculation in simple mode
     */
    val calculatedBySM: Boolean

}