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
Phone/Health Connect ingestion,
post-exercise recovery and premeal/meal-policy owners still need integration,
along with settings UI and full lifecycle scenarios. Automatic sensor-cadence
wiring and RT sleep telemetry are still open. Several unsupported opt-in policies explicitly reject a direct
factory call. Full source-policy coverage is required before removing the gate.
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

Latest local checkpoint (2026-10-28): 1177 Boost JVM tests and 7 selected
OpenAPSBoostPlugin host tests passed with no failures/errors/skips. The Boost
module assembled, including common/native metadata and iOS test compilation.
The original 114 source blobs, ML assets, 90 stored key defaults, source contract
and static isolation checks pass. Night policy parity includes a 27,648-context
comparison against the extracted original method. For the concurrent Russian
localization work, 175 selected pump regression tests also passed.

The earlier checkpoint covered 345 APS host tests and 23 SafetyPlugin tests;
those broad suites were not rerun for this increment. See the night/sleep
reports and [Russian translation report](../../RUSSIAN_TRANSLATION_20261028.md)
for the current scope. Full-debug phone and Wear APKs also assembled; their v2 signatures verify.
The phone APK includes the night/sleep owners and original model assets, with
no JVM oracle classes. New Russian resource values were checked in the APKs
and the embedded CWF watchface. Apple/device tests are not executed here.

Local debug assembly cannot establish success of a signed remote release build.
No patient/device validation or Apple test execution is claimed.
