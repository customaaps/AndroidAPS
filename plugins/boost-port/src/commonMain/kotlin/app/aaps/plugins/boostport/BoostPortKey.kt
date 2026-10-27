package app.aaps.plugins.boostport

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef

/**
 * Namespaced settings for the KMP Boost port. These must not be mistaken for the
 * legacy dosing preferences: the live APS algorithm is not yet integrated.
 */
enum class BoostPortKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef
) : BooleanPreferenceKey {
    AuditRequested(
        key = "boost_port_audit_requested",
        defaultValue = false,
        title = TextRef.Literal("Boost V6 integration diagnostics"),
        summary = TextRef.Literal("Engineering-only migration flag; never enables insulin dosing.")
    );

    override val preferenceType: PreferenceType get() = PreferenceType.SWITCH
    override val defaultedBySM: Boolean get() = true
}
