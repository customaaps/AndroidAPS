# V1 determine_basal replay gate (2026-10-27)

The existing `BoostV1DetermineBasalReplay` gate does not execute the archived
V1 function. It combines the ported entry CGM branch with validated autosens, meal, SMB-history,
rescue settings, profile and IOB snapshots for deterministic **offline** replay
preflight. It does not return insulin dosing instructions or register as APS.

The later [full V1 replay core](V1_FULL_REPLAY_ENGINE_20261027.md) executes
the complete calculation separately. Its JVM tests also execute the original
archived implementation as a reference, with controlled platform boundaries.
This does not make the older partial envelopes or invocation snapshots complete,
and does not connect the replay core to live APS.

The original source still depends on AndroidAPS Boost-specific profile data,
autosens, meal data, ML feature calculation and legacy temp-basal helpers.
Calculating dose parity requires independent reference and candidate execution
on identical recorded glucose/IOB/profile/meal histories, followed by the
existing strict output comparator. Synthetic unit tests do not show this parity.

CI exercises the new gate, existing JVM tests, Android KMP compilation and
fullDebug APK. Passing these tests must not be interpreted as clinical validation.
