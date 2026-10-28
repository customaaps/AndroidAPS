# Boost APS integration checkpoint

Target: `nigthscout-dev-boost-port-20261026`. Source remains
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`; V2 is excluded.

## Connected boundary

`OpenAPSBoostPlugin` is registered as an APS plugin and calls the portable
V1 → V5 → V6 pipeline through `BoostApsCalculatorFactory`. Its target inputs
include constrained basal/IOB limits, canonical pump units, CGM/IOB/meal data,
temporary targets, profile percentage, source activity/history preparation,
TDD and the shared Dynamic CR policy. The original profile ISF/CR remain
separate from calculated values. The output is a standard `APSResult` with
`Algorithm.BOOST`, predictions, source telemetry and same-cycle Overview values.
There are no pump-delivery calls in the adapter; the existing Loop owns delivery.

The factory owns a sequential calculation session under `boost_aps_session_v1`,
separate from the offline replay key. First use can import source V1/V5 state;
corrupt state and persistence failures reject the calculation. Import/stop
invalidate the owner and prevent stale results or old storage writes from
replacing imported settings. Cancellation propagates. Invalid/nonfinite output,
partial temp-basal results and SMB output against an incoming denial are rejected.
Calibration events inhibit SMB for 15 minutes; fallback requests retain their
SMB denial. The 90 stored source settings/defaults/export flags are checked
against a hash-pinned extraction from the source branch.

## Deliberate remaining gate

The new APS plugin is **hidden and forcibly disabled**. Registration and
injection do not make it a selectable dosing algorithm. Source end-of-cycle
sleep/history updates are now connected through the sequential
[sleep owner](APS_SLEEP_LIFECYCLE_20261028.md). The source
[night-mode SMB policy](APS_NIGHT_POLICY_20261028.md) is also connected.
[Sleep/HR telemetry and BOOST persistence](APS_SLEEP_TELEMETRY_20261028.md)
now cross the APS/storage/Nightscout boundaries.
The [policy/data/settings checkpoint](APS_POLICIES_AND_DATA_20261028.md) connects
post-exercise recovery, premeal learning/targets, tight-ramp trials, the V5 floor
history gate, phone steps, Health Connect heart rate, HR dark-feed notifications
and 67 source settings with Russian labels. These opt-in policies no longer
reject a direct factory call solely because the owner is missing.
[V5 automatic configuration and scheduled re-derivation](APS_AUTOCONFIG_20261028.md)
are now connected. The [bounded history-repair owner](APS_HISTORY_BACKFILL_20261028.md) and
[Health Connect step-history/baseline owner](APS_STEP_HISTORY_20261028.md)
are now staged; these increments are uncompiled/untested and use `[skip ci]`
per the user's instruction to verify at the end. Automatic sensor-cadence wiring,
newer upstream 6.2 features in the KMP port and remaining activity/V5/V6
telemetry/lifecycle scenarios are still open. Full integration coverage is required before removing
the gate.
The earlier GENERAL inspection plugin also remains hidden and disabled.

## CI investigation

Run `40512952583` ended with runner shutdown/cancellation. Its available log
does not establish a Kotlin compiler error or an out-of-memory cause.
`branch-ci.yml` now builds app and wear sequentially with two workers, a bounded
4 GiB heap and Kotlin compilation in process. Resource diagnostics were added.
The Kotlin strategy uses the correct project property (`-P`). Signing arguments
remain individual array elements, including passwords containing shell-special
characters. A fresh remote run is needed to assess the shutdown mitigation.

## Validation

Latest automatic-configuration checkpoint: 1218 Boost JVM tests and 13 selected
APS/settings host tests passed without failures/errors/skips. Frozen-source
comparisons cover 1440 onboarding cases and 324 re-derivation steps. The Boost
module and full-debug phone/Wear APKs assembled; APK v2 signatures verify.
See [the auto-configuration report](APS_AUTOCONFIG_20261028.md) for exact scope,
rounding correction, artifacts and remaining work.

Previous sleep/HR checkpoint (2026-10-28): 1185 Boost JVM tests, 8 APS converter
tests, 3 SQLite tests, 10 result-wrapper host tests, 7 Boost APS host tests and
2 Nightscout transport host tests passed with no failures/errors/skips (1215 total). The Boost
module assembled, including common/native metadata and iOS test compilation.
The original 114 source blobs, ML assets, 90 stored key defaults, source contract
and static isolation checks pass. Night policy parity includes a 27,648-context
comparison against the extracted original method. The earlier Russian
localization checkpoint also passed 175 selected pump regression tests; they
were not rerun for this result/persistence increment.

The earlier checkpoint covered 345 APS host tests and 23 SafetyPlugin tests;
those broad suites were not rerun for this increment. See the night/sleep
reports and [Russian translation report](../../RUSSIAN_TRANSLATION_20261028.md)
for the current scope. Full-debug phone and Wear APKs also assembled; their v2 signatures verify.
The phone APK includes the night/sleep owners and original model assets, with
no JVM oracle classes. The earlier translation checkpoint checked Russian resource values in the
APKs and embedded CWF; the resource audit still passes for this increment. Apple/device tests are not executed here.

Local debug assembly cannot establish success of a signed remote release build.
No patient/device validation or Apple test execution is claimed.


## Staged device-age increment — 2026-10-28

The [device-age telemetry helper](APS_DEVICE_AGE_20261028.md) now reads cached
infusion-site/reservoir change times and appends the source tags to the APS
reason. Seven targeted regression scenarios are authored but not executed.
This increment uses `[skip ci]`; earlier validation numbers above do not cover it.
