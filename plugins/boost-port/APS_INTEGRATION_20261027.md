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
injection do not make it a selectable dosing algorithm. Source sleep/history
updates, phone/Health Connect ingestion, night-mode orchestration,
post-exercise recovery and premeal/meal-policy owners still need integration,
along with settings UI and full lifecycle scenarios. Existing sleep state is
read, but this checkpoint does not claim to update that state as the source
plugin does. Several unsupported opt-in policies explicitly reject a direct
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

Passed: 1135 Boost JVM tests, 345 APS host tests and 23 SafetyPlugin tests, with
zero failures/errors/skips. Boost module assemble passed, including common/iOS
metadata and iOS test compilation. The full debug APK assembled and its signature
verified. Its DEX contains the actual APS plugin, calculator factory, Android
model source and invalidatable storage owner; source model assets are byte-exact
and JVM reference classes are absent. Source archive/model/transformation,
90-key-default and isolation checks, workflow YAML/Bash syntax and whitespace
checks passed. The additional import-during-input-collection test ensures that
an invalidated cycle cannot create a new owner from stale inputs. Publication
and lifecycle invalidation also share a lock.

Local debug assembly cannot establish success of a signed remote release build.
No patient/device validation or Apple test execution is claimed.
