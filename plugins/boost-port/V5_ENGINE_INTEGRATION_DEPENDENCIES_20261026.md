# Boost V5 engine dependency handoff — source-verified, no live integration

Source: `agniabakush/AAPS-3.4.2.3_Dmitry`, branch `3.4.2.6-Boost_V6_261026`.
Target: `nigthscout-dev-boost-port-20261026`.

## Essential original production components

| Original file | Purpose | Missing target integration |
| --- | --- | --- |
| `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/OpenAPSBoostV5Plugin.kt` | AAPS APS plugin, user-facing settings, lifecycle and APS invocation | No active Boost V5 APS plugin registered in target |
| `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/DetermineBasalBoostV5.kt` | Observe–Confirm–Commit dosing orchestrator | No connected target APS dose engine |
| `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/V5StateStore.kt` | State persistence through `Preferences` | Only passive state audits, no preference-backed store |
| `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostV5AutoConfigApply.kt` | Writes eight managed `DoubleKey` preferences | Missing active managed keys, write guards and user-setting protections |
| `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/BoostDosingPreferences.kt` | Bridges dosing settings | No corresponding live target bridge |
| `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/OapsProfileBoost.kt` | Extended profile data | No active profile adapter |

## Concrete Kotlin/Android dependencies verified in source

`OpenAPSBoostV5Plugin.kt` imports Android `Context`, AndroidX preference widgets, Dagger `@Inject`/`@Singleton`, `javax.inject.Provider`, `dagger.Lazy`, `JSONObject`, AAPS `APS`/`APSResult`/`IobTotal`/`Profile`, `Preferences`, `PersistenceLayer`, `TddCalculator`, `ConstraintsChecker`, `ActivePlugin`, `UiInteraction`, `BoostRiskModel` and `OpenAPSBoostPlugin`. It delegates `specialEnableCondition`, max-IOB and basal constraints, and plugin configuration to the original Boost V1 engine. Hence copying it alone would not provide a functioning or safe independent V5 engine.

`DetermineBasalBoostV5.kt` is a separate injected orchestration class. The target already contains isolated pure V5 algorithm helpers, but not this production orchestrator connected to APS invocation.

`V5StateStore.kt` directly depends on the AAPS `Preferences` interface; the target's state-continuity audit cannot replace persistence without a reviewed migration policy.

## Safe integration order / acceptance conditions

1. Compare target and source AAPS interfaces, Android resources and dependency-injection wiring; preserve Dmitry's existing correction order.
2. Establish exactly how the eight V5 managed stored IDs map to target key declarations, with Simple Mode, migration, stored-value and auto-config safeguards. Do **not** auto-apply archived defaults to insulin-related preferences.
3. Port and compile a **disabled** adapter for profile/data conversions and state persistence, without live APS invocation or pump permissions.
4. Independently review safety gates and fallback paths; compare with source behavior using recorded offline inputs and regression tests, including loss-of-CGM and restart cases.
5. Only after independent clinical/device review consider plugin registration or live dosing changes. No live APS modifications are authorized by this document.

**Status:** documentation-only bridge for actual engine migration; no tests or CI executed. Successful older CI `40352751901` predates this document.
