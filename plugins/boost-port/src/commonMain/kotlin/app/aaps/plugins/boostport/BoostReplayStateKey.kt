package app.aaps.plugins.boostport

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/** Isolated replay state, not a setting or a legacy live-APS state key. */
internal enum class BoostReplayStateKey(
    override val key: String,
    override val defaultValue: String = "",
) : StringNonPreferenceKey {
    Session("boost_port_replay_session_v1"),
}
