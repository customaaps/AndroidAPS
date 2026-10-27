# V1/V5 full calculation with source ML models

Source branch: `3.4.2.6-Boost_V6_091026`, commit
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. Destination:
`nigthscout-dev-boost-port-20261026`. V2 remains excluded.

## Implemented

- `V1ReplayRiskTreeModel` and `V1ReplayMealTreeModel` implement the full
  source inference for the bundled 53-feature/100-tree risk model and
  8-feature/50-tree meal model. The risk adapter also implements the source's
  cached-vector projection at hypothetical IOB, without accumulating projections.
- `BoostMlAssetParser` is shared by JVM replay and the explicit Android asset
  reader. It checks the header, ordered feature names, tree count, every node,
  depth, node budget, finite model values and input size. Corrupt/incompatible
  models produce an unavailable result, never a partial tree list. Android
  retains its bounded stream read and closes the asset stream.
- `AndroidV1ReplayModels.load` explicitly loads these models for a caller-owned
  replay session. Nothing invokes it automatically. Schema validation is stricter
  than the old source loader; on valid bundled assets the score arithmetic is
  identical. The older strict diagnostic scorer is unchanged. The risk adapter
  preserves the original nonfinite-feature right branch, while the meal adapter
  preserves its original direct `<=` comparison. This is source compatibility,
  not a newly claimed property for invalid dosing input.
- `V1ReplayCycle` carries all full-engine inputs explicitly.
  `BoostV1V5Replay.run` executes V1, builds V5 inputs from its result, and calls
  the full existing `DetermineBasalBoostV5.decide`. The returned frame includes
  both calculations and V5's next state for offline comparison. No sensitivity
  or Autosens adjustment is recalculated in the handoff.
- `V5ReplayContext` carries recorded settings and external state. The source's
  `activeMode` is simulated only; it does not activate this port. Opt-in floors
  and primer default off. Floor activation retains the source's conjunction of
  selected mode, requested setting and externally established TBR allowance.
  No Preferences/database read or permissive TBR default is added.
- V1's feature history can be restored/exported as typed snapshots. V5 state
  remains caller-owned and uses the existing legacy persistent-field bridge;
  transient score streaks are correctly lost at a simulated process restart.

Replay instances and model caches belong to one sequential session. Do not
share one risk cache across independent replay streams. The returned V5 input
callback is evaluated against the session's current vector, as in the source;
it is not an immutable historical scorer for delayed use.

## Source comparisons

`tools/v1_replay_sources.py` now verifies these additional original Git blobs:

| Source | Git blob |
| --- | --- |
| BoostRiskModel | `4775125e345eb41e2b613fb38142540d69c272fa` |
| BoostMealModel | `3cb022777238019a2f6ae52c33ff1236a9d5fd05` |
| OpenAPSBoostV5Plugin | `082573a05735f83323a78cae757c5661cd430918` |

The JVM test reference retains the models' original `JSONObject` parser,
Java exponential, traversal, vector caching and projection. Only Android asset
reads, DI and logging are adapted. The portable implementation uses its own
KMP parser/scorer, so the comparison does not call the candidate as its oracle.
The V5 input oracle extracts the actual plugin's `buildInputs` and 30-minute
guard helper, substituting recorded settings, time and history-read outcomes.
Generated reference sources remain JVM-test-only.

The ten model tests compare 2,000 risk and 2,000 meal vectors, all risk split
thresholds and adjacent doubles, nonfinite feature routing, size mismatch,
projection/cache ownership, malformed assets, and 25 full V1 cycles with actual
model scoring. Five additional chain tests cover all V5 input fields, the
first-six-points guard and fallbacks, contextual gates, missing-model fallback,
and 36 full V1/V5 cycles with actual models, resets and state restoration.
The full-cycle tests compare every V1 result field and diagnostic string,
V5 inputs, the complete V5 decision and next state, and require nonzero V5
candidates so a permanently blocked route cannot pass the test unnoticed.

All **989 Boost JVM tests** passed locally with zero failures/errors/skips.
`:app:assembleFullDebug` passed in the same Gradle invocation. APK signature
verification passed; DEX inspection confirmed the replay engines/model adapters
are present and the generated reference namespace is absent. Both model assets
inside the APK exactly match the verified source files. APK size: 247,873,237
bytes; SHA-256: `e0d260c589f4cfdbd4c51094c64dc40188194b1794b8aa33030e541978955bd8`.

These are synthetic inputs using the actual archived model assets; no recorded
patient outcomes or device execution are claimed. The source's cumulative-SMB
limitation documented in [the full V1 checkpoint](V1_FULL_REPLAY_ENGINE_20261027.md)
is preserved.

## Remaining application work

At this checkpoint the runner stopped at the V5 decision. The subsequent
[final V6 override checkpoint](V6_FINAL_OVERRIDE_REPLAY_20261027.md) adds that
stage as a separate replay candidate, without conversion to `RT`/`APSResult`. Live
profile/history assembly, Preferences/state lifecycle, plugin registration,
settings integration and actual device/ART verification remain outstanding.
The older partial preflight reports retain their original readiness flags.
The registered inspection plugin remains hidden, GENERAL and forcibly disabled.

SMS Authenticator disablement and the engineering-mode switch are retained.
Changes are local; this checkpoint does not claim a commit, push or remote CI.
The later [General-switch checkpoint](ADDITIONAL_SWITCHES_20261027.md) records
the subsequent requested settings and the latest APK checksum.
