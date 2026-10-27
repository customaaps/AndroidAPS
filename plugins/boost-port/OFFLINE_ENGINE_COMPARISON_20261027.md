# Offline APS engine result comparison checkpoint

Introduces a fail-closed, caller-fed common Kotlin comparator for already recorded reference and candidate insulin/temp-basal **numeric fields**. It does **not** call DetermineBasalBoost, DetermineBasalBoostV5, OpenAPSBoostPlugin, AAPS runtime or any device, and does not decide whether a dose is safe. Matching numeric outputs does not establish safe behavior or clinical parity.

A separate clinical safety and integration review must address SMB timing, pump delivery increments, basal durations, IOB state, profile changes, CGM reliability, hypo guards, failure modes, delayed deliveries, alarm handling and controlled device tests before any live behavior is contemplated. Dmitry's ISF/Autosens ordering remains untouched.

This commit includes JVM tests plus a fix for nested Kotlin class references in BoostTddTransitionOfflineReplayTest. Full GitHub Actions verification is requested by a commit without [skip ci]. Boost V2 excluded.
