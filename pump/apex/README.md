# Apex TruCare III

Android pump driver integrated into the full and pump-control builds through the existing pump-driver project discovery. Select **APEX TruCare III** in Config Builder and enter the pump's eight-digit serial in its settings. Selecting this plugin and enabling Loop remain explicit user actions.

Source: `Vlad747kja/AndroidAPS`, branch `3.4.2.6-Boost_V6_261026`, pinned commit `ae43a2f1d2879a1f72b02108020c0e66edc1f620`. Original author attribution is retained. The source includes Bluetooth reconnect/password handling, pump history synchronization and interrupted/uncertain bolus tracking. Packet formats and pure driver decisions are preserved.

The integration replaces Dagger and the XML fragment with Metro bindings and a Compose screen/settings. It publishes typed pump amounts through StateFlow, uses native notifications and BolusProgressData, and bridges the existing transport workers to suspend PumpSync APIs. Limits enter the current cU-aware PumpPluginConstraints boundary; IU limits are not reapplied to native pump amounts. TDD amounts are converted to IU separately because that PumpSync method still takes doubles.

History reconciliation can correct bolus amount/type while preserving an existing AAPS timestamp. `updateExistingTimestamp` defaults to true for all other callers. Apex passes false when importing pump history because its minute-resolution timestamps must not replace the recorded delivery time.

Extended boluses remain unsupported, as in the source driver, and are not advertised as a capability. Fill-history reading remains an optional engineering diagnostic and does not insert therapy records.

## Validation status

Verified in GitHub Actions on 2026-10-29 (Asia/Krasnoyarsk):

- [fullRelease duplicate-class check and fullDebug APK build](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40762750488/job/123078722232): passed. The debug APK is retained as an artifact of that run.
- [Boost APS activation, preference/lifecycle, metadata, CGM cadence, Loop and application-graph integration tests](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40762750488/job/123078722265): passed.
- [Boost common/JVM tests, exact reviewed-source transformation checks and Android KMP module assembly](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40763892562): passed after removing an extra trailing blank line in V6ReplayOverride.kt.

The APK and integration jobs tested the Joda dependency fix in c49f2f8; the repeated engine job also includes the format-only correction in 0242cad. The initial engine job in run 40762750488 failed that strict whitespace comparison and is superseded by the successful engine run linked above.

The original 3.4 source tests remain unchanged under `upstream-tests` with a `.source` suffix. They are reference material, not runnable coverage for this integration. No physical Apex Bluetooth session or on-device insulin delivery was verified by these checks.
