# V1 offline result envelope status

`BoostV1OfflineRtEnvelope` now gathers the existing immutable ISF, BGI,
eventual-BG, IOB/COB/ZT/UAM and LGS/SMB-condition reports in one place.
It can also attach the prediction-safety timeline and the subsequent
[carbohydrate-requirement comparison](V1_CARB_REQUIREMENT_SHADOW_20261027.md),
with consistency checks against the same cycle's inputs.

This is deliberately NOT the legacy RT or AndroidAPS APSResult. It does not
contain an actionable temp basal, insulin request, bolus size or pump command.
The source-equivalent dosing decisions and exact 1:1 replay remain missing.
An envelope marked SNAPSHOT_READY means only the partial mathematical
snapshots are available, **not** that the original algorithm has been ported.

Next prerequisites: map every remaining V1 profile and IOB field, reproduce
source decision order, compare original and candidate on the same recorded
cycles with the parity harness, and run the final complete CI suite.

## Prediction guard continuation — 2026-10-27

Destination: `nigthscout-dev-boost-port-20261026`. Requested source:
`3.4.2.6-Boost_V6_091026` at `bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`.
The archived `port-source/DetermineBasalBoost.kt.source` matches the source
engine's Git blob `65b56ebb9a9db6d5b5cc3a8c020f152dfc2fd0a1` exactly.
All 114 blob entries in `SOURCE_MANIFEST.md` also match that requested
source commit. Boost V2 is excluded from this continuation and from that
manifest.

The prediction guard now accepts source-supported zero-carb IOB/UAM paths.
It requires a defined COB/carbs fraction only when the selected branch uses
that fraction; contradictory or incomplete inputs still return no result.
Its rounding now follows source `Math.round` semantics, including positive
and negative half values, instead of Kotlin's ties-to-even rounding.

`BoostV1PredictionGuardBlendTest` adds 15 regression cases for those paths,
COB/UAM blending, zero-temp boundaries and missing data.
`BoostV1PredictionSafetyTimelineTest` adds nine cases for the source's
five-minute horizons and strict predicted-low / 30%-delta SMB vetoes.
These cases exercise offline helpers, not a full source-engine replay.
Validation in this cloud instance: `:plugins:boost-port:jvmTest` completed
with 955 tests, zero failures/errors/skips (including these 24 new cases).
The four static isolation/asset/source-contract/snapshot checks also passed.
Android APK compilation and device validation are separate checks; this
JVM result does not establish a complete V1/V5/V6 migration.

Integration checkpoint for the local working tree based on `8d92fc7de8`:
Boost JVM tests (955), SMS Communicator Android host tests (27), and
`ConfigImplTest` (4 engineering-mode cases) all passed with no failures,
errors or skips. `:app:assembleFullDebug` completed successfully and produced
`app/build/outputs/apk/full/debug/app-full-debug.apk`. No GitHub Actions run,
remote push, release APK or device/ART validation is claimed by this check.

## Full calculation checkpoint — 2026-10-27

`BoostV1ReplayEngine` now contains the complete source calculation behind a
separate replay-only result/profile contract. The original calculation is
also compiled into JVM tests, with Java math and its original feature-ring
codec retained as the reference. Six source engine safety cases and 13 new
source-comparison tests pass. The current Boost suite totals 974 tests with
zero failures/errors/skips; the new fullDebug APK build also passed.

This does **not** upgrade the older partial `BoostV1OfflineRtEnvelope` into
a source-equivalent RT: that envelope still lacks a complete input adapter.
See [full replay status and the cumulative-SMB source limitation](V1_FULL_REPLAY_ENGINE_20261027.md).

## Full model and V1/V5 chain checkpoint — 2026-10-27

The full source V1 calculation now runs with actual 100-tree risk and 50-tree
meal inference, followed by source-compatible V5 input assembly and the full
V5 decision core. All 989 Boost JVM tests passed, including 15 additional model
and full-chain tests. See [the model/chain evidence](V1_V5_MODEL_REPLAY_20261027.md).
This does not upgrade the older partial envelope's flags or apply the final V6
override/caps. Live APS/profile/state lifecycle remains disconnected.

The subsequently requested General source-capability and version-check switches
passed all 64 selected constraints tests; the final APK built and its signature
was verified. See [switch behavior and final artifact](ADDITIONAL_SWITCHES_20261027.md).
V2 remains excluded. All changes remain local, without commit/push.

## Final V6 override, persistent replay and Russian settings — 2026-10-27

The standalone chain now includes the source final V6 override and a persistent
replay session for V1 history/V5 state. All 1012 Boost tests pass, including
source model, override and storage/restart comparisons. The final APK builds,
its signature is verified, and all 338 shared setting strings have packaged
Russian values with original abbreviations preserved. V2 remains excluded.
See [current implementation and verification](V6_FINAL_OVERRIDE_REPLAY_20261027.md).
Live data/profile assembly, application lifecycle and APS integration remain
outstanding; this checkpoint does not change the older partial readiness flags.
Publication status is tracked separately in Git; remote CI/device validation is
not inferred from these local results.
