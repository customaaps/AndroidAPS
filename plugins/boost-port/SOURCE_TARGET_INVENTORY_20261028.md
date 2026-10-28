# V1/V5/V6 source-to-target inventory — 28 October 2026

Source: tim2000s/Boost-in-AAPS_3.4 at `3f75d6a36c84e048c5ddf8a5dfce935184842d11`.
This accounts for 38 Kotlin implementation units in openAPSBoost/openAPSBoostV5
and the shared OapsProfileBoost contract: all 39 implementation units in the
original port inventory. V2/V7 and other experimental algorithms are outside this port.
[Machine-readable inventory](SOURCE_TARGET_INVENTORY_20261028.json) records each
source path, immutable Git blob, target entry point and status. Mapping a file does
not establish behavioral/clinical parity; the checkpoint reports carry validation.

All four current model/metadata JSON blobs match the frozen source archive exactly;
their immutable Git hashes are also recorded in the JSON inventory.

## Reviewed calculation differences

The latest V5 engine differs from the frozen archive only in named argument order;
the state store change is a comment about the already-restored wall-clock anchor.
HR classification, meal-history and Health Connect step-ingest differences are
comments. The source now removes two defensive ML guards and the phone counter
reset/spike cap; target hardening is retained. Current auto-config, tranche,
sleep, device-age, insulin-need, history and cadence increments have their own
pinned transfer reports.

V1's current source adds static-ISF autosens application in determine_basal and
removes this fork's Dynamic CR path. Those changes are deliberately not applied:
Dmitry's ISF/Autosens correction order and the shared Dynamic CR integration are
requirements of this target. The post-rescue named source constant remains 75
mg/dL, equal to the target's default; the explicit target trial remains separate.
This eligibility block retires the fixed Boost clock window and brings current
night/sleep/learned-lie-in/genuine-wake policy into the active owner.

## Remaining diagnostic parity

The persisted live ISF-EMA and VWA-TDD counterfactual shadows are still represented
by pure helpers, rather than source live state plus Nightscout fields. The source
V1 minGuard curve-name breadcrumbs and boostVersion configuration metadata also
remain different. These are actual trace/telemetry gaps, not evidence that the
entire source has been copied. Existing target Overview/Wear/platform UI is reused
rather than replaced with source widgets. Device tests of selected Boost/Loop and
real sensor/pump timing remain separate from JVM/host/build verification.

| Source unit | Target entry point | Status |
|---|---|---|
| `BoostIsfShadow.kt` | `BoostV1IsfShadowCalculation.kt` | diagnostic_partial |
| `BoostMealModel.kt` | `V1ReplayTreeModels.kt` | adapted |
| `BoostMlFeatureBuilder.kt` | `V1ReplayFeatures.kt` | adapted |
| `BoostRiskModel.kt` | `V1ReplayTreeModels.kt` | adapted |
| `BoostV1SafetyRules.kt` | `BoostV1SafetyRules.kt` | adapted |
| `BoostVwaTddShadow.kt` | `BoostVwaTddShadowMath.kt` | diagnostic_partial |
| `DailyStepHistoryTracker.kt` | `DailyStepHistoryTracker.kt` | adapted |
| `DetermineBasalBoost.kt` | `BoostV1ReplayEngine.kt` | adapted_with_diagnostic_gap |
| `DeviceAgeTag.kt` | `DeviceAgeTag.kt` | adapted |
| `HealthConnectHrIngest.kt` | `BoostHealthConnectIngest.kt` | adapted |
| `HealthConnectPermissionActivity.kt` | `BoostDataPermissionsActivity.kt` | adapted |
| `HealthConnectPrivacyActivity.kt` | `BoostDataPermissionsActivity.kt` | adapted |
| `HealthConnectStepsIngest.kt` | `BoostHealthConnectStepHistory.kt` | adapted |
| `HrActivityCalculator.kt` | `HrActivityCalculator.kt` | adapted |
| `HrSourceResolver.kt` | `HrSourceResolver.kt` | adapted |
| `MealTimeLearner.kt` | `MealTimeLearner.kt` | adapted |
| `NightWindow.kt` | `NightWindow.kt` | adapted |
| `OpenAPSBoostPlugin.kt` | `OpenAPSBoostPlugin.kt` | adapted_with_diagnostic_gap |
| `SleepHistoryTracker.kt` | `SleepHistoryTracker.kt` | adapted |
| `SleepStateDetector.kt` | `SleepStateDetector.kt` | adapted |
| `StepFeed.kt` | `StepFeed.kt` | adapted |
| `StepService.kt` | `BoostPhoneStepTracker.kt` | adapted |
| `StepSourceResolver.kt` | `StepSourceResolver.kt` | adapted |
| `WearStepSource.kt` | `WearStepSource.kt` | adapted |
| `AggressionBudget.kt` | `AggressionBudget.kt` | adapted |
| `BoostHistorySync.kt` | `BoostHistorySync.kt` | adapted |
| `BoostV5AutoConfig.kt` | `BoostV5AutoConfig.kt` | adapted |
| `BoostV5AutoConfigApply.kt` | `BoostV5AutoConfigApply.kt` | adapted |
| `ConfirmTrancheController.kt` | `ConfirmTrancheController.kt` | adapted |
| `DetermineBasalBoostV5.kt` | `DetermineBasalBoostV5.kt` | adapted |
| `InsulinNeedWatch.kt` | `InsulinNeedWatch.kt` | adapted |
| `MealActionMultiplier.kt` | `MealActionMultiplier.kt` | adapted |
| `MealHypothesis.kt` | `MealHypothesis.kt` | adapted |
| `MealSignalScore.kt` | `MealSignalScore.kt` | adapted |
| `OpenAPSBoostV5Plugin.kt` | `BoostApsCalculatorFactoryImpl.kt` | adapted |
| `SafetyGates.kt` | `SafetyGates.kt` | adapted |
| `TrancheThresholdDeriver.kt` | `TrancheThresholdDeriver.kt` | adapted |
| `V5StateStore.kt` | `BoostReplayStateCodec.kt` | adapted |
| `OapsProfileBoost.kt` | `BoostTargetProfileAdapter.kt` | adapted |
