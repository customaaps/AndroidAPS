# V1 carbohydrate-requirement replay stage

Continuation from `8d92fc7de8e15260a041508f531d0cf3ad1da7c3` on
`nigthscout-dev-boost-port-20261026`.

`BoostV1CarbRequirementShadow` extracts lines 1039–1048 of the archived
`legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/DetermineBasalBoost.kt.source`.
It consumes the preceding prediction-safety timeline and preserves:

- Original **profile** sensitivity divided by the effective carbohydrate ratio
  for CSF, separately from the selected cycle sensitivity used for zero-temp effect.
- Positive dynamic carbohydrate-ratio override, unrounded zero-temp effect,
  and `max(0, mealCOB - 0.25 * carbs)` subtraction.
- Source `roundToInt` rounding, including negative results and half ties.
- Inclusive carbohydrate threshold and 45-minute reporting window.

`BoostV1OfflineRtEnvelope` optionally attaches this diagnostic after validating
the safety timeline and matching the cycle's basal, sensitivity, original profile
sensitivity and meal inputs. The preceding timeline also checks prediction
impact/remaining impact and the adjusted minimum target when provided.

`BoostV1PredictionGuardBlend` now accepts valid zero-meal IOB/UAM paths,
without manufacturing a carbohydrate ratio. Undefined weighted blends still
fail closed. Its whole-number rounding follows the source's ties towards
positive infinity rather than Kotlin's ties-to-even `round`.

Local validation: all **956 JVM tests passed**, with zero failures or skips,
including **25 new cases** across carbohydrate arithmetic, no-meal prediction
guards, prediction-safety timeline boundaries and envelope consistency.
ML assets, disabled-plugin isolation, archived V5/V1 source contract and all
114 archived Git blobs passed their existing Python checks.
`:plugins:boost-port:assemble` also completed, including Android AAR output
and common/native metadata compilation. No iOS device tests were run.

The full `:app:assembleFullDebug` check is **blocked**, not passed: the
environment's restricted network proxy returned HTTP 403 for the existing
`com.github.kenglxn.QRGen:android:3.0.1` POM on `jitpack.io`, required through
`appshell` / `plugins:sync`. The environment draft includes that destination;
apply the network settings and rerun the APK build without changing dependencies.

These fields remain offline comparison evidence. They do not create an RT,
notification, treatment entry, APS registration or pump action. Source-equivalent
V1 orchestration, full recorded-cycle parity and live integration remain open;
the existing readiness gate still rejects full RT parity. Boost V2 is excluded.

Follow-up on 2026-10-28: the continuation now uses consolidated remote base
`2f28ab3b857eaef0ca683e66ae1d360646160140`. The `jitpack.io` POM read succeeds;
the network failure above describes the earlier run. See the current
[sleep-owner integration and validation](APS_SLEEP_LIFECYCLE_20261028.md).
