# Boost port in Dmitry's nightscout-dev branch

This directory holds a **partial**, disabled Kotlin Multiplatform compatibility port of selected Boost V1/V5/V6 components from `agniabakush/AndroidAPS:dev`. It is not a complete Boost integration, and is not suitable for dosing or pump control.

- The Metro registration is a hidden GENERAL inspection plugin, forcibly disabled; it is **not** an APS plugin.
- Pure commonMain helpers and commonTest tests were transferred individually.
- The 114-file legacy snapshot is retained under `legacy-snapshot`; Kotlin files have a `.source` suffix. `verify_legacy_snapshot.py` verifies their original Git blobs, which also match the requested `3.4.2.6-Boost_V6_091026` source.
- The full V1 calculation now runs as an isolated `BoostV1ReplayEngine` with explicit clock, formatting and model boundaries. See [the full replay checkpoint](V1_FULL_REPLAY_ENGINE_20261027.md) for source comparisons and remaining limitations.
- Actual risk/meal models, full V1→V5 calculation and the final V6 override now run together for caller-owned replay. See [the model/V5 handoff](V1_V5_MODEL_REPLAY_20261027.md) and [final V6 override](V6_FINAL_OVERRIDE_REPLAY_20261027.md); the live plugin remains disconnected.
- `BoostReplaySession` restores/saves its V1/V5 state through an explicit KMP storage boundary. Shared preference resources have complete Russian coverage (349/349); abbreviations are preserved.
- Full source ISF preparation and a 73-field target profile adapter are now available. Dynamic CR is a shared General setting used by SMB/AutoISF/AMA and the Boost adapter. See [profile/CR integration](SHARED_CR_PROFILE_20261027.md) for current scope and limitations.
- The equipment card now includes same-cycle ISF/CR values; complete source activity/sleep/learning helpers and a target history/cycle assembler are staged. See the [Overview/activity checkpoint](OVERVIEW_ACTIVITY_20261027.md) for validation state and remaining live integration.
- Boost V2 remains excluded at user request; source-origin legacy tests and the separately staged read-only resolver tests must not be confused.
- Passive, unregistered Android adapters now exist for step sensor events and explicit Health Connect HR/step reads. They do **not** run automatically, write AAPS persistence, or register an APS algorithm. No Nightscout backfill or live pump control is wired.
- Do not change the Dmitry Autosens/ISF correction order or apply a duplicate ratio adjustment.

## Verified build baseline

GitHub Actions run [40295120159](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40295120159), on commit `040d65fe1bd2298d8db005f952afd0ef4218554b`, completed successfully on 2026-10-26:

- Boost ML asset validation: passed.
- Disabled/hidden Boost port isolation guard: passed.
- `:plugins:boost-port:jvmTest`: passed.
- `:app:assembleFullDebug`: passed; APK artifact `dmitry-boost-port-full-debug-apk` uploaded.

This is a **build and isolated-unit-test milestone, not clinical validation or proof of a complete port**. Later commits must be checked against their own CI runs.

## Remaining integration work

1. **V1 Android adapters:** passive diagnostic sensor and HC read bridges exist, but Android lifecycle registration, permission-request UI, on-device verification, data ingestion, and AAPS persistence are **not** implemented.
2. **V1 engine integration:** the complete arithmetic is available for offline replay; `OpenAPSBoostPlugin`, live input assembly and APS invocation remain disconnected.
3. **V5 engine integration:** legacy `DetermineBasalBoostV5`, `OpenAPSBoostV5Plugin`, state store, and configuration application are not wired into live APS.
4. **V6 scope:** verify the precise V6 change set against the source manifest and port each approved change independently; do not assume V5 helpers mean V6 is complete.
5. **Legacy verification:** inventory the original snapshot and port relevant tests, then run regression tests for autosens/ISF correction order, dosing parity, pump safety, and device compatibility before considering activation.

**Activation gate:** until these items and independent medical-device safety checks are complete, keep the inspection plugin hidden, GENERAL, and forcibly disabled. No live insulin delivery is authorized by a successful CI build. Boost V2 remains excluded.

## Passive Health Connect device-verification checklist

The Android-only `AndroidHealthConnectReadOnlyAdapter` is an **explicit-call**
diagnostic boundary; there is no settings switch, background scheduler, or
in-app entry point that invokes it. Automated CI can compile it but cannot
prove operation against a user's Health Connect installation.

Before considering a separate, user-triggered *read-only* diagnostics UI:

1. Inspect the final **merged manifest** for only
   `android.permission.health.READ_HEART_RATE` and
   `android.permission.health.READ_STEPS` from this module. Never add health
   write permissions, background access, or activity recognition automatically.
2. Confirm that an unavailable Health Connect SDK is treated as
   `SDK_UNAVAILABLE` and revoked/missing read permissions as
   `PERMISSION_REQUIRED`; no query is made before permission validation.
3. On a test device, grant and revoke each permission **independently**.
   Verify that missing permissions do not leak data and that zero records
   are distinguishable from denied/failed/incomplete reads.
4. Check devices with no HR data, absent wearable, stale records, future
   timestamps, source duplicates, interrupted pagination, and a repeated
   page token. Failures must not be represented as zero heart rate or steps.
5. Check actual permission rationale and privacy-policy requirements before
   adding a user-facing Health Connect permission flow.
6. Keep the inspection plugin hidden, GENERAL and forcibly disabled.
   Never connect the read results to Dmitry dosing, basal, SMB, or pump APIs.

The `HealthConnectPageGuard` and its common tests reject cyclic continuation
tokens and truncated multi-page reads. The Android adapter discards partial
results on page failure. These checks are not clinical validation.

Latest **completed, confirmed** pre-pagination checkpoint:
[workflow 40303853663](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40303853663),
commit `24eb33845d4dd7d71540466916f9cfada84cd37e`.
Later pagination commits require their own successful CI run.

## Additional staging after the October 26 test deferral

The last externally confirmed passing APK/JVM/disabled-plugin workflow before
these additional staging changes was
[40315838035](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40315838035)
at `0bb63b3a`. Subsequent files must not be described as tested merely because
their commits have been saved. The user requested that general verification be
deferred until the transfers are complete.

A following V6 meal-time diagnostic and its tests were staged in
`cbf7d9f0` and `9542d3f0` (workflow 40316920969 was started before the
deferral). **No success claim is made here for that workflow.**

New, additional isolated work staged with `[skip ci]` commit messages:

- `dda9721d` and `fbfb8c42`: read-only V1/V4.4.2-style ISF EMA
  shadow comparison, with 10 targeted common tests. This does **not** change
  Dmitry's original correction sequencing.
- `3d6bd67b` and `667e244e`: read-only VWA-TDD projection/day-curve
  arithmetic, with 13 tests. This is **not** the full legacy persisted VWA
  implementation and does not recommend doses.
- `a6ebe1ea`, `8834cc0b` and `3e3f6d81`: KMP LightGBM
  leaf traversal for offline parity, 10 tests and a staging-only test
  compilation-reference correction. No JSON loader or live inference.
- `806e5779` and `05bb6bd0`: V5 passive meal-signal score with 12 tests
  and the original corrected ML-outage reweighting; the active meal state
  machine and dose decision are **not** ported.
- `fa4608b6` and `780f538d`: read-only V5 onboarding history-gap policy
  with 11 tests. It does **not** invoke NSClient or write a database.

These commits are unverified staging until the final complete-suite build.
The scope is intentionally partial: legacy engine, live settings application,
active V5 state machine and tranche delivery, V1/V5/V6 integrations with AndroidAPS, clinical
behavioral parity, on-device Health Connect, permission UI, database backfill
and pump-side validation remain outstanding. **Do not activate Boost or
represent this module as clinically safe.** Boost V2 is excluded.

## V5 replay and evidence stage — verification requested

The following source-parity components were added after the deferred batch:

- `cd58eb11`: **passive V5 meal state replay**, including 4-minute
  age cadence, 30-minute evidence expiry, 90-minute session lock and
  persistence-independent reset behavior. Outputs are diagnostic state
  labels only, never insulin delivery instructions.
- `09c7c63c`: 22 common tests for replay transitions, confirmed-meal
  single-session lock, clock jumps, annunciated meals and old evidence.
- `b670a133`: **passive V5 tranche evidence**, containing only a
  fitted logistic evidence score and historic 7.5–30-minute inspection
  window. It does not hold or release any insulin.
- `5ebf0e3e`: 11 common tests for that mathematical diagnostic.

These additions do not implement active
`DetermineBasalBoostV5`, `OpenAPSBoostV5Plugin`, `V5StateStore`,
SMB tranche delivery or UI configuration.

**Verification gate:** Per user request, the staging phase ends here and
the complete GitHub Actions check should run on this documentation commit:
static disabled-plugin guard, all Boost KMP/JVM tests and fullDebug APK.
A successful build is not clinical validation and must not be interpreted
as authorization to turn on live APS Boost. Boost V2 remains excluded.

## Passive V5 configuration/state staging — tests deferred again

The latest fully verified CI baseline before this additional stage was
[40319273778](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40319273778)
on commit `2f3b10b6` (JVM tests, static isolation, and fullDebug APK passed).
The user then requested further porting **without intermediate checks**.
The following commits have `[skip ci]`; **none of their new tests have been
verified by a subsequent suite or APK build**:

- `ea3bd589`: `BoostV5StateSnapshotCodec` holds the full legacy V5
  state *field* schema as a typed, immutable String map, with older field
  defaults and strict corrupted-input rejection. `b385db14`: 11 tests.
  There is no JSON parser, Android Preferences backend, persistent cache,
  NS telemetry export or live state import. The historic single-session
  commit lock is preserved in data, not activated for dosing.
- `35321eab`: `BoostV5ConfigResolutionAudit` computes hypothetical
  per-knob resolution using current and historical factory defaults, user
  overrides, severe/ordinary time-below-range raise guards, and the
  operative-cap cumulative suggestion. `ff70d984`: 12 tests.
  This is NOT the live `BoostV5AutoConfigApply` settings writer.
- `03f9d568`: `BoostV5RedriveAudit` stages only the FOUR tracked
  settings' movement/baseline rules (25% step cap, hysteresis, noise
  deadband and raising guard). `46b128fd`: 11 tests.
  Periodic scheduling and computed cumulative/primer updates remain
  outside this helper, as do all preference writes.

The new changes are **34 staged tests without a test run**, and are not a
complete adaptation of legacy V5/V6. In particular, no live ISF/dosing
integration, pump commands, SMB transfer, auto-config I/O, Nightscout
history sync, Android lifecycle or Health Connect background invocation
has been enabled. Dmitry's correction order is unchanged.

**Remaining work before describing the port as complete:** map each
source legacy file to the target, identify uncovered logic and
dependencies, complete the non-dosing data adapters and state/
configuration compatibility, run the requested final CI suite, then
independently validate therapy-related safety. Successful CI alone does
not authorize activating Boost as an APS algorithm. Boost V2 is excluded.

## V5 state-continuity stage and full verification request

The following passive additions were committed after the prior verified build
40319273778:

- `a994cbb3` and `0eab270c`: KMP V5 wall-clock evidence tracking, plus
  14 tests that cover four-minute ML null ticks and score-ready streaks.
- `b45868d4` and `9f5aff65`: KMP in-memory-before-persisted state
  precedence and stale-state inspection, plus 10 tests. The component does
  not actually write Preferences, persist JSON, or run a background cache.
- `cde3d779`, `328121e2`, `6a67d384` and `4e76e5a6`: explicit
  nested type references in recently staged Kotlin tests, fixing compile
  hazards before the final test request.

Per user request the staging block ends here and the current commit triggers
GitHub Actions with the KMP/JVM tests, passive isolation guard and
`fullDebug` APK build. All post-baseline changes are **unverified until the
workflow finishes successfully**. Runtime clinical safety is not implied.
The disabled inspection plugin has no APS/Pump/SMB connection, and Dmitry's
ISF correction order is unchanged. Boost V2 remains excluded.

## V1/V6 activity-history shadow stage — no CI per user request

Starting from the last confirmed successful verification
[40325646611](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40325646611)
at commit `67e5f58c`, the following independent KMP files were added with
`[skip ci]` commits. **They have not been compiled or tested in GitHub Actions.**

- `833cacd2`: `DailyStepActivityDiagnostics`, a passive read-only
  extraction of the legacy 28-day completed-step history, hold-higher
  merge, seven-day baseline requirement, 24–48-hour weighted activity
  deviation and same-day expected-pace comparison.
- `bf9b3323`: 16 activity-history and invalid-input test scenarios.
- `0228c91e`: `IntradayStepBankDiagnostics`, a pure per-source maximum
  bank that retains a watch's highest pre-midnight cumulative step count
  even after its counter resets before the phone's midnight.
- `45060fbe`: 12 bank-rollover and source-alias test scenarios.
- `cc33c9ee` and `c61a2d77`: `StepHistoryBridgeDiagnostics`
  and a staging-only naming adjustment. The source's phone-anchored
  cross-wearable normalization, three-day calibration threshold,
  highest-count day selection and uncalibrated source marking are
  represented independently of any live sensor/service/APS module.
- `bfe9e8b5`: 12 watch-swap, missing-overlap, hold-higher and
  diagnostics-only regression scenarios.

**This stage contains three passive helpers and 40 newly authored unit
tests. All of them are UNVERIFIED until the next explicitly requested
workflow run.** This is NOT the full `DailyStepHistoryTracker`: no JSON
migration/persistence, permission UI, HistorySync reader, Android
activity/step data service, production data source, or active ISF adjustment
has been connected. Existing Dmitry insulin calculation/ISF ordering remains
unchanged; Boost V2 excluded. Future APS/pump integration requires
independent safety review, not just CI green status.

## Passive sleep and step-service stage — verification deferred

Following the last verified GitHub Actions run
[40325646611](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40325646611)
(commit `67e5f58c`), the user requested continued one-file-at-a-time
porting **without intermediate builds or test runs**.

This additional stage contains three READ-ONLY commonMain helpers and 38
new authored commonTest scenarios, all unverified:

- `870c00d3`, `fbdb79cd`, `91f43c84`: the immutable
  `SleepSessionHistoryDiagnostics` around source `SleepHistoryTracker`'s
  confirmed sleep-start/close, rolling 28-day window, HR p10 summaries,
  and genuine wake-reason inclusion. 13 tests, followed by a test-only
  readability change.
- `eb487bab`, `e1773d3c`, `3a1021ba`, `20db7d57`:
  `SleepWakeEvidenceDiagnostics` — the source's 60m
  positive-step-growth window, watch-upload lump tolerance, reset-safe
  cumulative counter handling, 10m HR-sustain cadence and minimum
  reliable fresh HR count. 13 tests plus a pruning refactor and
  an offline-corrected boundary assertion.
- `9095a3a6`, `a0555c91`, `599ca721`:
  `StepBucketWindowDiagnostics`, preserving legacy StepService's
  five-minute bucket history, strict completed-window cutoff, raw
  counter-baseline and 1500-increment sanity guard. 12 tests, plus
  correction of the legacy 20/21 edge-bucket bound in this KMP replay.

**No new tests were run and no new APK was built during this stage.**
The modules only accept caller-supplied offline samples, with no sensors,
AAPS Preferences, Nightscout, CGM listeners, scheduler, automatic profile
adjustments, insulin/SMB calculations or pump commands.

The original SleepStateDetector live AWAKE/PRE_SLEEP/SLEEPING transitions,
Health Connect ingestion, Android step listener lifecycle and other
platform integrations are NOT ported by this stage. Existing Dmitry ISF
correction timing and the production algorithm remain unchanged; Boost
V2 stays excluded. Future live therapy integrations would need
independent clinical safety validation beyond successful CI.

## Health Connect HR and steps policy staging — final test request

The user requested to run complete verification after finishing the Health
Connect diagnostic staging block. Three source-independent, read-only KMP
helpers were added with six distinct source/test commits:

- `f86e8093` / `04751a2c`: `BoostHcHrPollDiagnostics`
  (18-hour resweep, five-minute throttle, permission/SDK/in-flight guards);
  12 regression tests.
- `fd5fe644` / `76ded6b0`: `BoostHcHrBackfillDiagnostics`
  (Garmin late-upload lookback, minute-level live-feed precedence,
  deduplication and invalid-input rejection); 14 tests.
- `d4d741ec` / `399f0ecf`: `BoostHcStepSnapshotDiagnostics`
  (bounded 28-day paginated snapshot, coverage-based source choice,
  separate per-source/phone daily totals, duplicate detection and
  cross-midnight quality metadata); 14 tests.
- `0bd60502`: correct a newly staged poll-boundary assertion, without
  changing any diagnostic implementation.

**All 40 new tests and the additional tests staged since passing run
40325646611 are pending the new CI run triggered by this checkpoint.**
The workflow includes the disabled-plugin isolation check, all Boost
KMP/JVM tests and fullDebug APK compilation. No test success is
claimed before the run finishes.

These components DO NOT read Health Connect, request Android permissions,
write HR/step data to AAPS, issue background syncs, register with the
production loop, or modify the existing Dmitry ISF calculation, profile,
SMB or pump. Boost V2 is excluded. A green build is not validation of
live therapeutic behavior and does not authorize enabling Boost dosing.

## Sleep state signal and phone step ledger stage — not tested

Last fully verified technical baseline: GitHub Actions
[40330272669](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40330272669),
commit `3b7b0b19`, all Boost JVM tests, isolation and fullDebug APK
passed. **All changes listed below come AFTER that result and have NOT
been compiled, run as tests, or used in a new APK.** In accordance with
the user's instruction, every subsequent commit uses `[skip ci]`.

- `6a64eea3`, `8bcffdb2`: `SleepHrTransmissionDiagnostics`
  copies the October source decision to require step corroboration for
  batched HR resume events in the core night, while retaining the
  90-minute scheduled-wake and lie-in exceptions. 14 new tests.
- `ab841d3c`, `5bd24db7`: `PhoneStepDayLedgerDiagnostics`
  stages the phone's separate reset-tolerant steps-since-local-midnight
  accumulator and HC *hold-higher* seed. 13 new tests.
- `e99b408b`, `81ebd42c`, `634344bb`:
  `SleepScheduleWindowDiagnostics` stages the source half-open
  night/pre-sleep/early-candidacy/wake-grace/lie-in windows, with
  12 tests and an unambiguous local-clock interval expression.
- `537829e9`, `e9b24907`: `SleepPhaseReplayDiagnostics`
  stages the original AWAKE/PRE_SLEEP/SLEEPING branch precedence
  as immutable offline labels, with 16 tests covering hysteresis,
  drought, steps, resume bursts and non-genuine boundary exits.

This stage comprises FOUR newly staged passive commonMain helpers
and 55 new authored commonTest scenarios, without a verification run.

These components intentionally accept caller-provided snapshots only.
None registers sensors or a plugin, invokes Health Connect or Nightscout,
writes preferences, enables automatic sleep-mode suppression, adjusts
insulin dosing, SMB/basal/profile/target, or sends pump commands.
This is not the complete `SleepStateDetector`, `StepService` or Boost
V1/V5/V6 live integration. In particular, background sensors,
data-store schema/permissions, end-to-end event sequencing and all
clinical behavior validation remain unresolved. Dmitry's correction
timing remains untouched. Boost V2 is excluded.

## Night clock and step-feed availability — tests deferred

The most recent **verified** full technical baseline remains
[GitHub Actions 40330272669](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40330272669),
commit `3b7b0b19`. Later V1 sleep and phone-ledger staging through
`e3ce20ba` and this new stage have NOT been compiled or run in CI.

Current passive source-parity additions, each committed individually
with `[skip ci]` per user's instruction:

- `caabae26`: `NightClockWindowDiagnostics` stages original
  `NightWindow`'s half-open wrapped clock fact, intentionally independent
  of any night-mode policy switch. Invalid time coordinates abstain.
  `6e41ed37`: 10 new KMP test cases.
- `df26413c`: `StepFeedSafetyDiagnostics` stages `StepFeed`'s
  feed-availability distinction (phone/12m Wear), unknown-vs-zero step
  interpretation, night/asleep/lie-in exclusions and *hypothetical*
  lie-in failsafe state. `b6d57c0c`: 15 new KMP tests.
- `a1def15b`: bring the existing pure `StepSourceResolver` into
  alignment with legacy's explicit rejection of negative coverage and
  negative step counts. `381771c9`: four new tests.

This stage therefore contributes **29 unexecuted test cases**.
These functions return only immutable offline diagnostics. They do not
subscribe to live sensor feeds, alter profiles/targets/SMB/ISF, register
an active APS plugin, access Preferences, operate a pump, or write
therapy settings. Dmitry's production correction ordering is untouched
and Boost V2 is excluded.

Remaining active-platform parity, end-to-end data adapters, engine
source gaps, comprehensive regression and independent therapy safety
validation are NOT complete. Green CI in earlier runs does not
validate the new, unexecuted tests or authorize Boost activation.

## TDD-transition and ML-ring schema stage — complete CI requested

The previous verified CI baseline is
[40330272669](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40330272669)
(commit `3b7b0b19`). A later, initially deferred stage
up to `04cc61ba` contains passive V1 clock and step-feed predicates,
and sleep, phone-ledger and step-source diagnostics. The user now
requests a **complete verification after this block**.

Source-parity changes committed independently in this block:

- `a8746aee`: `BoostTddSettingTransitionAudit`, an immutable inspection of
  legacy V1/V6 TDD-enabled, adjustSens, V6-mode-change and
  sensitivity-velocity preference-transition rules, without Preferences I/O.
  `6e569f79`: 13 KMP test scenarios.
- `94835f07`: `BoostMlRingSchemaAudit`, a strict portable String-map
  field schema for BoostMlFeatureBuilder's v12 6-snapshot ring. It
  delegates offline lag trimming to the already isolated
  `BoostMlFeatureDiagnostics`; no JSON, model loading, CGM reader or
  persistent write is performed. `747e085e`: 12 KMP test scenarios.

The checkpoint commit triggers the complete GitHub Actions workflow:
disabled/hidden-plugin isolation guard, ML asset validation, all
Boost-port JVM tests (including **25 newly authored scenarios in this
block**) and `fullDebug` APK compilation. **Do not claim successful
verification until GitHub Actions reports a completed success.**

The entire port remains diagnostic-only, and does not change or connect
to Dmitry's live ISF correction, insulin recommendations, SMB/basal or
pump controls. Boost V2 is excluded. Full original live-engine porting
and independent clinical safety validation are still outstanding.

## V6 meal history and projected-risk schema audits — tests deferred

Technical baseline **last successfully verified**: GitHub Actions
[40334692717](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40334692717),
commit `a36422b3`, including common/JVM tests, disabled/hidden plugin
isolation, ML asset checks and the `fullDebug` APK. The additions below
are AFTER that verified commit and use `[skip ci]` per the user's
continuing instruction to port files separately without interim checks.

- `0504caf7`: `MealEventHistoryAudit` — immutable offline staging of
  Boost V6's 60-day *confirmed* meal event history and the
  `{"events":[timestamp]}` field vocabulary, plus strict chronology
  checks, repeated-event deduplication and configured-night filtering.
  No Preference or JSON I/O. `d42b0850`: 12 unexecuted KMP tests.
- `2a5c9156`: `MealLeadWindowAudit` — passive inspection of the
  original V6 learned-meal clock-distance window (45 minutes to
  `max(configuredLead, 55)` minutes before a mode), reusing the existing
  offline `MealTimeDiagnostics` minimum six confirmed events/four
  distinct days and night filtering. This returns only evidence labels,
  NOT a pre-meal temporary glucose target or dose calculation.
  `7641a2fe`: 13 unexecuted KMP tests.
- `aa36f6e9`: `BoostRiskFeatureProjectionAudit` — offline copy-only
  model feature rewrite from source `BoostRiskModel.predictAtProjectedIob`.
  Preserves the original v12 duplicate lag0 current-IOB fields and
  projects bolus-IOB/recent-SMB feature values consistently while leaving
  lag1+ historical features unchanged. No ML inference or risk threshold
  or insulin recommendation is produced. `6034963f`: 12 unexecuted
  KMP tests.

This stage adds **three inert KMP commonMain helpers and 37 authored
commonTest scenarios** in six distinct source/test commits.

**No JVM tests, GitHub Actions or APK build has been run for these
post-baseline commits.** A later requested CI run must compile and
exercise all of them, in addition to the disabled-plugin static guard.
This stage is not full live integration of `MealTimeLearner` or
`BoostRiskModel`; Android Preferences, model loading, scheduling,
real clinical safety evaluation, APS registration, and production
therapy pathways remain intentionally disconnected. Dmitry's
correction ordering and actual pump/SMB/IOB behavior remain unchanged.
Boost V2 stays excluded.

## Explicit-read Android ML asset staging — tests deferred

The previous successfully verified full CI is
[40334692717](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40334692717)
(commit `a36422b3`). The immediately preceding staged V6 meal/risk-schema
block through `8dd68fde`, plus this block, have not been run in CI.

The source assets already exist in the app module under
`app/src/main/assets/boost/`: `hypo_risk_model.json` and
`meal_likelihood_model.json`. The following additional files were
committed separately with `[skip ci]` as requested:

- `7ed89d84`: `BoostMlAssetSchemaAudit`, immutable KMP format validation
  of the source v12 risk model (53 **ordered** features, 100 trees)
  and V1 meal-likelihood model (8 ordered features, 50 trees), checking
  both branches of every supplied tree for bad splits/leaves, excessive
  depth and total node budget. `391bd884`: 12 new commonTest cases.
- `6dcec86f`: `AndroidBoostMlAssetInspection`, an explicit-call-only
  Android resource reader that parses exactly those two app-level JSON
  assets into the existing `BoostMlTreeDiagnostics.Model` tree carrier,
  rejects missing/oversized/mismatched assets and never performs model
  scoring, patient-data collection, scheduling or persistence. This
  is NOT registered as a service, is NOT automatically invoked, and
  returns only model structure for manual/offline inspection.
- `18330ea3`: `BoostMlLegacyVectorAudit`, a pure eight-field feature
  packing projection matching legacy `BoostMealModel` and the
  compatible `BoostRiskModel` entry point. A 53-feature v12 risk schema
  is *not* allowed to take the eight-feature fallback. `ae9573f9`:
  10 commonTest cases.

**Total: three additional source helpers (one Android-only), 22
authored new commonTest scenarios, all unexecuted.** No new JVM tests,
GitHub Actions run, Android build or APK verification was performed.
In particular, success in an earlier CI does not establish that
this new Android reader compiles or can parse the installed resources.

No work in this block changes Dmitry's insulin correction order,
the AAPS dosing algorithm, live profile/target/SMB/IOB calculation,
pump operation, ML inference routing, settings registration or access
permissions. Boost V2 remains excluded. Original Boost V1/V5/V6 live
engine integration and independent therapy-safety validation remain
outstanding.

## Bounded Android LightGBM asset inspection — CI checkpoint

Last confirmed baseline: GitHub Actions
[40334692717](https://github.com/agniabakush/AAPS-3.4.2.3_Dmitry/actions/runs/40334692717)
at `a36422b3`. The V6 meal and risk-audit staging at `8dd68fde`,
and the previous ML asset staging at `365a11e7`, were committed
without CI. The user requested a complete test/build run at the
completion of THIS additional Android asset-reader hardening block.

The current staging adds each file separately, with `[skip ci]` until
this checkpoint commit:

- `ea1a2c74`: `BoostMlAssetHeaderAudit`, a pure KMP guard that requires
  the bundled LightGBM model header to declare exactly 100 trees / 53
  features for hypo-risk-v12, or 50 trees / 8 features for meal
  likelihood. Both must declare `objective=binary` and
  `average_output=false`.
- `73f983bd`: eight KMP tests covering matching headers, swapped models,
  changed objectives, tree/feature mismatches and invalid averaging.
- `707fc152`: harden `AndroidBoostMlAssetInspection` to read with a
  strict one-megabyte streaming limit (not post-read), verify the
  same header before materializing trees, and reject any split whose
  decision type differs from `<=`. This is a parser/schema boundary
  only. No live model inference is performed.

The source APK assets were inspected: both JSON trees expose the
declared headers and all of their split decisions use `<=`. This is
source data inspection, NOT a successful Android build, parser runtime
execution or end-to-end model acceptance result.

This checkpoint triggers GitHub Actions for the accumulated Boost
common/JVM tests, read-only isolation guard, resource validation,
and `fullDebug` APK compilation. New Android reader logic is compiled
as part of the APK stage, but not directly exercised by commonTest.
**Do not report that tests or APK pass until CI finishes successfully.**

Boost remains disabled and does not change Dmitry corrections,
SMB/IOB, profiles, glucose targets, insulin delivery or pump code.
Boost V2 remains excluded, and independent therapy safety validation
is required before any future live integration.



## 2026-10-27 offline source-parity checkpoint

- `36f942dc` and `552451db`: isolated archived V1 profile-implied TDD plausibility predicate and offline boundary tests. The historic predicate's non-finite handling is retained **for comparison only**, not approved for clinical use.
- `dd835b18`: independent 15-field V5 persisted-state schema tests against the archived `V5StateStore` contract, including missing required keys, legacy defaults and invalid numeric/lock fields.
- `8b6593de`: offline V5 encode/decode matrix spanning all five meal phases, lock/primer state and anomalous clocks.

These are new isolated tests, **not** a live engine port or clinical safety validation. There is no Boost APS registration, pump invocation or change to Dmitry's Autosens/ISF correction order. This checkpoint requests a fresh CI run to compile and execute the new tests and build the fullDebug APK; do not claim success until GitHub Actions reports it. Boost V2 remains excluded.
