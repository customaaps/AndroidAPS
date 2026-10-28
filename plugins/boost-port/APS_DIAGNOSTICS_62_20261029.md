# Boost 6.2 diagnostic transfer

Source: `tim2000s/Boost-in-AAPS_3.4` at `3f75d6a36c84e048c5ddf8a5dfce935184842d11`.
Working-branch base: `81dec3fd50e77c1e09e81b304b9b089918fc77c9`.

The APS owner captures the ISF preparation and its original TDD parts once. Only
when that preparation entered the TDD-derived calculation does the completed
V1/V5/V6 cycle perform the optional comparisons. Source ISF-EMA state is read and
written under `boost_isf_shadow_state`; cold-start, elapsed-time EMA and legacy
JSON shape are preserved. Its counterfactual insulinReq/SMB fields use the V1
candidate before V6 override, as upstream does, not the final V6 candidate.

The full source VWA-TDD implementation (population seed, day anchor, calibration,
curve folding, previous-day fallback, history warm-up and JSON state) is
mechanically adapted from immutable blob `a0787acec53c0411b5579f5b6ddc39e712d1b2da`.
The hour-window semantics and 48 warm slices are preserved, not redesigned.
Exactly empty offset slices contribute zero without a target TDD query: the target
returns null when such an interval has no samples. Missing nonempty history still
abstains; it is never filled with zero.
KMP supplies the codec, formatter and integer floor division; the caller supplies
the captured cycle time and suspending TDD reads. Source field names travel in RT.
Independent JVM oracles retain original JSON/platform arithmetic for comparison.

The owner writes only the two diagnostic state keys in one captured-state edit.
Cancellation propagates; changed/imported state cannot be overwritten by an old
history read. Invalid/future/nonfinite diagnostic state abstains. Optional history
or codec failures do not replace, zero or invalidate the completed dosing candidate.
The same candidate's ISF, CR, insulinReq, SMB, basal, predictions and constraints
remain independent of every diagnostic output. The state owner is recreated on
stop/import; persisted source state is restored on the next eligible cycle.

V1 reason and console traces now identify the minGuardBG curve (IOB, COB, UAM or
COB+UAM); RT also carries the label. Only trace-local assignment/output changes
are made to V1. Dmitry ISF/Autosens and shared Dynamic CR order remain intact.

`boostVersion` identifies source Boost 6.2.0 and its pinned upstream commit. Each
Boost RT carries it. The selected APS exposes read-only metadata to the target's
running-configuration settings document alongside the target application build.
The target had already removed legacy devicestatus configuration piggyback; it
is not reintroduced. Clients parse this metadata but never apply it to settings.
Older RT/configuration documents remain valid through nullable/default fields.

The user reported selection, settings and calculation checks on the device as
working on 28 October. This does not stand in for real sensor/pump delivery timing
or clinical validation. V2/V7 and their experimental instrumentation remain out
of scope. Source widgets are replaced by the target platform UI.

## Verification

Full regression and APK results were still pending at publication; this checkpoint
is not labelled fully tested. The already-started [CI 40733233356](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40733233356)
uses source commit `c16d7097185c1e38a38b3d87e9b9b4f3c6668624`. The working-branch
transfer preserves its production, test and workflow sources exactly and adds only
README, inventory and this evidence. It does not launch a duplicate CI run.

A local 1001-cycle comparison of the original pinned EMA implementation against
the portable formula passed. Repository regressions additionally cover persisted
EMA restarts, full-source VWA learning/day-roll/reload, missing/failed history,
malformed/future state, cancellation/import ownership, RT serialization, actual
APS connection and read-only configuration metadata. Their presence is not a
claim that the full suite has passed for this checkpoint.

The prior **1559-test and APK success** belongs to the earlier eligibility
checkpoint `81dec3f`, not this diagnostic transfer. The new APK still needs its
own successful build and device check.
