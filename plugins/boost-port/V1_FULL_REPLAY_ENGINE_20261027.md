# Full V1 calculation for offline replay

Source: `3.4.2.6-Boost_V6_091026`, commit
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. Destination:
`nigthscout-dev-boost-port-20261026`. Boost V2 is excluded.

`BoostV1ReplayEngine` ports the complete archived `DetermineBasalBoost`
calculation, including prediction loops, target/sensitivity branches,
low-glucose suspension, temp-basal selection, SMB tiers, risk scaling,
post-rescue behavior and max-IOB checks. It returns a separate internal
`V1ReplayResult`, which does not implement AAPS `RT` or `APSResult`.
No DI registration, preference writes, sensor access or pump connection is added.

The source's 73-field profile schema is preserved in `V1ReplayProfile`.
The caller supplies complete source inputs, recorded local time and diagnostic
formatting. ML models are explicit optional callbacks; the feature-ring
arithmetic is ported, with typed snapshot restore/export and no automatic JSON
or Preferences I/O. The source's object-form safety calls remain method
boundaries because its ART register-budget history makes inlining unsafe.
Dmitry's upstream Autosens/ISF adjustment order is untouched.

## Reference and checks

`tools/v1_replay_sources.py` verifies the original engine, feature-builder and
safety-rule Git blob hashes. It checks that the portable engine matches the
reviewed platform-only transformation. `generateV1ReplayReference` generates
test-only reference sources automatically before JVM test compilation.
The reference retains Java math, DecimalFormat, the original JSON feature-ring
codec and original safety helpers. Namespace, data types, clock and formatting
dependencies are adapted; dosing branches are unchanged. Reference code and
its JSON dependency are restricted to `jvmTest`.

`BoostV1SourceEngineParityTest` runs both full implementations on identical
synthetic input cycles and compares exact numeric outputs, all prediction
arrays, tiers, diagnostic reasons and traces. Coverage includes ordinary,
hypoglycemia, max-IOB, nonzero-SMB, unreliable/flat CGM, COB, dynamic carb ratio,
cumulative threshold, post-rescue cap, circadian clock, optional ML scores,
53-feature ordering, one/five-minute cadence, long gaps and one-time history
restoration. Models in these tests return controlled scores; this is not a
validation of actual ML model inference or recorded patient outcomes.

The six original `BoostV1SafetyEngineTest` scenarios now execute the complete
portable engine through `BoostV1FullReplaySafetyTest`. The current cloud run
passed all **974 Boost tests**, including these six and 13 source-comparison
tests, with zero failures/errors/skips. `:app:assembleFullDebug` passed on the
same working tree. No remote CI, release build or device/ART run is claimed.

## Cumulative-SMB source limitation

The archived code checks whether **past** 60-minute SMB volume has already
reached the configured cap. It does not reduce the current SMB to the remaining
headroom when past volume is still below the cap. A synthetic cycle with past
volume 0.15 and cap 0.2 can therefore propose more than the remaining 0.05;
at past volume 0.2 it blocks the SMB and reports `CUMULATIVE_SMB_CAP`.

This source behavior is preserved and explicitly characterized in the parity
test. Its comment describing a hard rolling-window bound is stronger than
the implemented check. Source equivalence is not proof of that bound.
Any change to the rule requires a separate reviewed algorithm change and
regression/replay validation before live integration.

## Still outstanding

- Full live-profile/data assembly and the V1/V5 plugin bridge.
- State persistence and application lifecycle wiring.
- Independent recorded-cycle provenance and source/candidate replay evidence.
- Android device/ART verification and review of the source limitations above.

The subsequent [model and V5 handoff checkpoint](V1_V5_MODEL_REPLAY_20261027.md)
adds real model loading/scoring and the complete caller-owned V1→V5 replay.
Its later test counts supersede the 974-test checkpoint above. It does not
connect the source's final V6 override or the live plugin.

The older partial diagnostic envelopes remain guarded; their readiness flags
are not upgraded by the presence of this standalone engine. The inspection
plugin stays hidden, GENERAL and forcibly disabled.
