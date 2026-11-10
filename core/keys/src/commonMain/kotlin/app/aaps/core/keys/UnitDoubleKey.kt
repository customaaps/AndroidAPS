package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val sync: SyncSpec? = null
) : UnitDoublePreferenceKey {

    OverviewLowMark(key = "low_mark", defaultValue = 72.0, minMgdl = 25, maxMgdl = 160, title = KeysStrings.pref_title_low_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewHighMark(key = "high_mark", defaultValue = 180.0, minMgdl = 90, maxMgdl = 250, title = KeysStrings.pref_title_high_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsLgsThreshold(
        key = "lgsThreshold",
        defaultValue = 65.0,
        minMgdl = 60,
        maxMgdl = 100,
        title = KeysStrings.pref_title_lgs_threshold,
        summary = KeysStrings.lgs_threshold_summary,
        defaultedBySM = true,
        dependency = BooleanKey.ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHalfBasalExerciseTarget(
        key = "half_basal_exercise_target",
        defaultValue = 160.0,
        minMgdl = 120,
        maxMgdl = 200,
        title = KeysStrings.pref_title_half_basal_exercise_target,
        summary = KeysStrings.half_basal_exercise_target_summary,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsDynIsfBgCap(
        key = "dynisf_bg_cap",
        defaultValue = 430.0,
        minMgdl = 108,
        maxMgdl = 450,
        title = KeysStrings.pref_title_dynisf_bg_cap,
        summary = KeysStrings.dynisf_bg_cap_summary,
        dependency = BooleanKey.ApsUseDynamicSensitivity,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    NightModeBgOffset(
        key = "night_mode_glucose_offset",
        defaultValue = 0.0,
        minMgdl = 0,
        maxMgdl = 90,
        title = KeysStrings.pref_title_night_mode_bg_offset,
        summary = KeysStrings.pref_summary_night_mode_bg_offset,
        dependency = BooleanKey.NightMode,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    )
    ;

}
