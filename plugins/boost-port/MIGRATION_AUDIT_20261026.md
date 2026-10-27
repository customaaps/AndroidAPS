# Boost migration audit — 2026-10-26

## Verified locations
- Source snapshot: `agniabakush/AndroidAPS:dev/plugins/boost-port/legacy/`. The inspected `DetermineBasalBoost.kt` blob matches the `65b56ebb9a9db6d5b5cc3a8c020f152dfc2fd0a1` entry in `SOURCE_MANIFEST.md`.
- Target of this audit: `agniabakush/AAPS-3.4.2.3_Dmitry:nigthscout-dev-boost-port-20261026`.
- Target `settings.gradle` includes `:plugins:boost-port`; `plugins/boost-port/build.gradle.kts` defines a KMP module with common, JVM, Android and iOS targets.
- The legacy `DetermineBasalBoost.kt` path is **absent** from the target branch. Do not confuse the source manifest with a completed target copy.

## Evidence / baseline
- GitHub Actions run 40295120159, commit `040d65fe1bd2298d8db005f952afd0ef4218554b`: Boost asset checks, hidden/disabled inspection-plugin guard, `:plugins:boost-port:jvmTest`, and `:app:assembleFullDebug` succeeded.
- Artifacts: `dmitry-boost-port-full-debug-apk` and `boost-port-test-reports`.
- These are build and isolated-test results; they do **not** validate therapy, dosing safety or a complete V1/V5/V6 port. Later edits require fresh CI.

## Remaining engineering work (not complete)
1. Inventory all 39 Kotlin sources, 4 ML assets and 71 legacy tests from `SOURCE_MANIFEST.md`; classify pure KMP helpers, Android adapters, APS engine, UI/keys and safety tests. Verify each destination separately.
2. Port non-actuating sensor ingestion/mappings (steps, heart rate, Health Connect) into appropriate Android interfaces, with explicit stale/missing-data rules.
3. Map V1 and V5 engine interfaces and defaults without registering a live APS implementation; verify the exact V6 delta before transferring it.
4. Preserve Dmitry's Autosens/ISF correction ordering, with explicit regression tests against duplicate ratio application.
5. Replay historic / simulated sensor and pump data, including hypo, stale CGM, maximum IOB, communications failure, exercise and fallback cases. Arrange independent medical-device safety review before considering any activation.

## Non-negotiable integration boundary
- Keep the inspection plugin GENERAL, hidden and forcibly disabled.
- Do not register Boost as APS, change pump commands, tune dose calculations or default insulin settings as part of this audit.
- Boost V2 is excluded, as requested.

## V5 schema migration passive checkpoint (2026-10-26)

After the successful run 40339006432 at `fa4d900d`, the following files were staged:
- `c2e5ba62`: read-only Kotlin migration preview for schema-v2 and legacy global-done settings;
- `83aaec86`, `b5c92191`: migration regression tests and correct enum import;
- `c08e8488`: additional fail-closed corrupted preference, empty legacy-done and idempotence tests.

All of this remains **inspection-only**, with no Preferences writes, APS registration, insulin calculation or pump integration. This audit-document commit intentionally triggers fresh GitHub Actions on the entire batch (Boost JVM tests, disabled-port isolation and fullDebug APK). Do not count this batch as verified until that run is successful; a successful build is not clinical validation.

## V6 passive ML continuity stage — CI deliberately deferred

- `17acf404`, `05b2787c`: isolated KMP historical meal-clock clustering, without the source's JSON persistence or pre-meal target activation.
- `8f46ec4c`: isolated six-slot, five-minute lag-grid inspection of caller-supplied historical ML snapshots, with explicit invalid, future, stale, cold-start and irregular-spacing findings.

These are **staged, uncompiled and untested** changes, committed with `[skip ci]` at the user's request. The lag-grid report does not run an ML model, attach to sensor data or validate glucose therapy. The existing diagnostic ring/schema helpers remain the only compatibility boundary; Boost V2 remains excluded. The original Dmitry APS, ISF/Autosens correction order, SMB and pump command flow are untouched.

## V6 passive feature ordering — verification deferred

- `fa8a23ed`: independent commonMain diagnostic schema for the original Boost v12 53 feature names, 17 static features and six successive 6-feature lag groups. Comparison is against a caller-provided name list only. No inference, insulin calculations, preference writes, pump commands or APS registration.

This block remains uncompiled and untested per user instruction. The schema audit alone does not establish equivalence with model assets or safe operation. Boost V2 is excluded and Dmitry's existing algorithm remains unchanged.

## V6 passive ML restart-history staging — CI deferred

- `4e3ac38e`: `BoostMlRestartHistoryAudit` consumes caller-provided historical field maps, validates them with the already isolated ring schema and lag-continuity audits, and classifies corrupt, future, stale, cold-start, irregular-spacing and complete restored histories.
- No Android persistence backend, JSON decoding, inference, live data, APS registration, glucose correction, SMB dosing or pump actions.
- No tests or CI were started for this change per user request. This block is uncompiled and unverified, and does not establish safe live operation. Boost V2 remains excluded.

## V6 persisted ML row-format staging — verification deferred

- `68475519`: read-only caller-parsed ML row-format check (`BoostMlPersistedFormatAudit`), comparing required legacy field labels, timestamp order, finite numeric fields and changes caused by ring restoration. Malformed rows are explicitly rejected instead of silently zero-filled.
- This is **not** a JSON serialization/deserialization implementation and does not read or write Preferences. No ML inference, insulin dosing, live APS registration or pump access is enabled.
- Compiling and testing this stage have been explicitly deferred by the user. Compatibility and correctness remain **unverified** pending a later test run. Boost V2 is excluded.

## Offline V6 restored-feature parity stage — CI deferred

- `216f8a1f`: isolated `BoostMlRestoredFeatureParityAudit` compares caller-provided, already-parsed historical ring fields against the declared 53-name v12 feature order, verifies that six historical records and a matching current sample exist, and cross-checks the built numerical diagnostic vector against its declared feature positions.
- No model inference, model loading, Android Preferences I/O, live CGM/IOB, APS registration or insulin/pump interface is involved. This helper makes no clinical safety claim.
- This staging is **not compiled or tested** as requested, and requires subsequent review/CI before considering it verified. Boost V2 excluded; Dmitry correction order unchanged.

## Offline ML feature/metadata block: CI checkpoint (2026-10-26)

- `89584fa0` corrects the model's six-lag feature ordering to feature-major order.
- `1f36c9c1` adds a read-only metadata/diagnostic-vector schema audit.
- `411039c9` rejects restored six-row ML histories whose five-minute lag spacing is inconsistent, instead of treating row count as sufficient evidence.
- All changes remain inspection-only: no inference, active APS registration, settings I/O, insulin dosing or pump control.

This documentation checkpoint triggers the full Boost port CI suite (isolation guard, JVM tests, and fullDebug APK). A green build confirms compilation and tested software checks only, not clinical/therapy safety. Boost V2 stays excluded.

## V6 meal ML metadata staging — CI deliberately deferred

- `7b023306`: adds an isolated, read-only validation of caller-parsed meal-likelihood metadata against the eight legacy input names, 50 trees, depth four, 15 leaves, 90-minute horizon, 0.65 acceptance gate and accepted model status.
- This does not load model files, score meal probabilities, alter preferences, register APS or affect insulin/pump decisions. Boost V2 remains excluded.
- The user requested no tests or CI for this stage; the new code is therefore uncompiled and unverified. Prior successful CI does not cover this commit.

## V6 dual ML asset schema audit (CI deferred)

- `098829d6`: added read-only cross-check of both inactive Boost model headers, feature-name ordering, and their shared eight-feature base.
- The audit accepts caller-provided parsed metadata only. It does not evaluate either model, change the Dmitry algorithm, write settings, or control insulin delivery.
- Tests and CI deliberately not triggered by request; compilation and parity remain unverified for this commit.

## V6 hypo-risk metadata provenance checkpoint (tests deferred)

- `20aa8932`: staged a read-only audit for the archived v12 hypo-risk metadata, including exact feature ordering, static/windowed partitions, six lookback cycles, 90-minute horizon, and archived training sample/user counts.
- The input is an offline caller-parsed metadata record. It does not load a model or enable inference; matching these constants is not evidence of clinical validity.
- Tests, CI and APK builds have not been triggered for this checkpoint at the user's request. Dmitry's live algorithm and pump interface remain unchanged.

## V6 paired metadata inspection (tests deferred)

- `dcfa0217`: stage a read-only comparison of both archived V6 ML metadata declarations. It composes the existing individual risk/meal metadata audits and checks their common feature prefix and declared prediction horizons.
- No inference, active APS registration, insulin calculation or pump I/O. This only compares caller-provided parsed metadata.
- CI, JVM tests, and APK build were not launched at the user's request; the new code is not yet compiled or verified.

## V6 restart-history future timestamp guard (CI deferred)

- `1130f08f`: inspect raw caller-parsed persisted history timestamps before resampling/replaying the ring. A future timestamp must not disappear silently when a close-spaced row is replaced during restoration.
- Passive diagnostics only. No live algorithm, insulin or pump changes. No tests or CI launched at user request; compile/behavior verification is pending.

## V6 cold-start lag spacing diagnostic correction (CI deferred)

- `805533f2`: in the passive ML lag-continuity audit, report irregular timestamp spacing ahead of incomplete (cold-start) history, so a short but malformed series is not classified only as incomplete. Stale/future and invalid inputs retain higher-priority checks.
- No live APS logic, dosing, SMB or pump paths changed. Tests and CI have not been run for this checkpoint at the user's request; the change is not yet verified by compilation.

## V6 restart history format preflight (CI deferred)

- `5d8047c9`: the passive restart-history inspector now performs the already-staged persisted-row format audit before checking restored lag continuity. Broken fields, invalid numbers and out-of-order timestamps fail closed rather than being conflated with cold-start or timing diagnostics. Explicit future timestamps remain reported before this preflight.
- No APS, dosing, SMB, preference writes or pump flow changed. Per user request, no tests, CI or APK compilation were triggered for this checkpoint.

## V6 ML restart fidelity status — tests deferred

- `91dc298f`: a six-row restored ML ring is no longer reported as exact `COMPLETE` when the caller-parsed persisted format audit found that replay normalized the input. A distinct passive `NORMALIZED_HISTORY` finding preserves this information; malformed, future, stale and irregular history checks remain in place.
- No source sensor data or live APS operations are touched. This stage is uncompiled and untested, with CI deliberately suppressed at the user's request.

## V6 ML restart-history regression test checkpoint

- `094c3120`: new read-only Kotlin regression tests cover six regular entries, malformed persisted fields, future timestamps, short irregular spacing, stale history and short-cadence ring replacement.
- This checkpoint triggers the existing GitHub Actions Boost KMP tests, isolation guard, ML asset validation and fullDebug APK build. The tests do not enable the Boost plugin or modify the active Dmitry APS algorithm.
- Passing CI cannot establish clinical safety of glucose or insulin decisions.

## V6 meal-clock input cap and remaining scope (CI deferred)

- `50828567`: limit passive V6 meal clock history inspection to 1,000 events to bound its quadratic neighbourhood search. More than 1,000 events results in no inferred modes, not any insulin or target action.
- `d7d1244b`: add unexecuted common JVM regression scenarios for insufficient evidence, six distinct days, duplicate timestamps, oversized history, and invalid night boundaries.
- These are staged only; no tests or CI were started per user request. The current port remains partial and disabled. The live V1/V5 basal engines, AndroidAPS registration, settings, persisted storage and therapy-level verification are outstanding; passing isolated CI does not imply completion or treatment safety.

## Source-to-target gaps and passive preference semantics (CI deferred)

- `968b26fa`: added `REMAINING_PORT_MAP_20261026.md` mapping live V1/V5 engines, Android registration, persistence, ML loaders, Health Connect and associated unimplemented dependencies. Current work is not close to a complete active Boost integration.
- `7d2547e0`: staged `BoostStoredPreferenceResolutionAudit`, a pure caller-supplied comparison of stored/factory values and Simple-Mode-masked reads. Includes the legacy UnitDouble requirement for a display-unit-converted default; no live Preferences or dosing code touched.
- No tests or CI triggered. These changes are not compilation-verified. The inspection plugin stays disabled, Boost V2 excluded and Dmitry's ISF/Autosens order untouched.

## Boost preference-read semantics regression staging (CI deferred)

- `e2bd6e1a`: adds six offline regression cases for raw stored values versus Simple-Mode-masked defaults, explicit false/empty overrides, caller-converted UnitDouble defaults and rejection of non-finite display values.
- These tests target the previously staged passive `BoostStoredPreferenceResolutionAudit`; no Android Preferences implementation or APS insulin calculation is involved.
- Tests and CI were deliberately not run. Passing the earlier workflow does not cover this commit; compile and test status remains unverified.

## Passive V5 persisted-state shape audit (tests deferred)

- `8950b0d9`: isolated field-shape audit on caller-provided V5 snapshot maps, distinguishing missing required keys, malformed values, legacy defaults, unknown future keys and the cache-only `lastCycleScore` field.
- `20d1ad5f`: six staged regression cases for schema migration and compatibility classifications. They have not been executed.
- No preferences reads/writes, live APS registration, insulin calculation, SMB or pump commands. CI was deliberately not started at the user's request; compilation and behavioral validation remain pending.

## V5 persisted-state forward schema protection (CI deferred)

- `3068e347`: the read-only V5 continuity inspector now rejects unknown future persisted fields or cache-only keys instead of silently treating a lossy decode as a valid restore. Supported older schemas with missing optional fields remain accepted. The in-memory cache retains precedence.
- `13bd884b`: four unexecuted regression cases cover older snapshots, unknown future keys, cache-only fields, and memory-cache precedence.
- No preferences writes, live APS registration, SMB/ISF change or pump operations. Testing and CI intentionally deferred; compilation not yet verified.

## V5 persisted state clock/lock regression stage (no CI)

- `8bdf3672`: four commonTest scenarios for passive V5 in-memory/persisted state continuity. These cover stale evidence without discarding historical commit lock, fresh evidence, future clock jumps, and unsupported future schema default handling.
- Tests have been staged but not executed at the user's request; compile status and correctness are unverified until the next full Boost workflow.
- No state persistence, actual APS registration, insulin, SMB, ISF or pump functionality added.

## V5 state-store operation ordering (tests deferred)

- `e92366cf`: staged a passive immutable operation trace for source V5StateStore save (cache update before asynchronous persistence request) and clear (cache invalidation before clear request). This performs no I/O and does not invoke APS.
- `35cca442`: three commonTest scenarios for sequencing and invalid-snapshot rejection; tests are not executed.
- No tests or CI launched per instruction. Changes remain uncompiled and unverified; no live insulin-delivery integration was added.

## V5 managed preference-key catalogue stage (CI deferred)

- `0ef8a632`: stage read-only catalogue inspection for the eight symbolic DoubleKey entries managed by original BoostV5AutoConfigApply, including the primer key. This does NOT map actual serialized preferences strings or write settings.
- Future mapping of target key declarations and version-specific defaults is still required. No tests, CI or APK build run per user instruction. Live APS, ISF order, SMB and pump control remain unchanged.

## V5 preference-key presence gap and requested CI checkpoint

- `e17e85b2`: a source-to-target comparison recorded in `V5_PREFERENCE_KEY_GAP_20261026.md` finds the eight original managed Boost V5 DoubleKey declarations absent from the target's current `DoubleKey.kt`; the existing symbolic key-map audit is not active settings integration.
- Current block is deliberately documentation-only: no dosing keys, preference writes, APS registration, SMB/ISF or pump changes.
- This checkpoint runs the complete existing Boost GitHub Actions verification after the user's explicit request: passive isolation guard, JVM tests, asset validation and fullDebug APK compilation. A successful run is software verification only, not medical-device clinical validation.

## CI 40349046748 failure and compile fix

- Workflow `40349046748` failed at `:plugins:boost-port:compileTestKotlinJvm`: test code mistakenly referenced the nested `Snapshot` type through an object-valued property (`codec.Snapshot()`). No APK build occurred.
- Commit `e8e40555` corrected both occurrences to `BoostV5StateSnapshotCodec.Snapshot()` and removed the unused alias. This documentation commit initiates a fresh full CI check of the fix.
- The V5 preference-key integration gap remains open and live insulin-control components remain untouched.

## CI 40349750084: second test-only nested-type correction

- Run `40349750084` failed in `:plugins:boost-port:compileTestKotlinJvm` at `BoostV5StateContinuityBoundaryTest.kt:16`: another use of `codec.Snapshot()` incorrectly treats a nested Kotlin type as a property of an object instance. Main Boost code compiled, but fullDebug APK was skipped.
- `b1e71f54` corrected the four occurrences in that test file to `BoostV5StateSnapshotCodec.Snapshot()`. This non-skip-ci documentation checkpoint requests a new full Boost CI run.
- No APS dosing, pump, SMB or Dmitry ISF order code was touched.

## Post-success V5 key declaration presence stage — no CI

- Baseline GitHub Actions `40350391288` completed successfully at `db78bf70` (Boost JVM tests, isolation, ML assets, fullDebug APK).
- `e1ef0b9f`: a new pure inventory inspection distinguishes all/partial/zero V5 managed key declarations in a caller-supplied target catalogue. It does not add DoubleKey declarations or interact with preferences.
- `cddbd40a`: four staged tests, deliberately not executed. Current target's `DoubleKey.kt` still lacks the eight legacy managed Boost V5 keys, blocking actual settings integration.
- No tests, CI or APK workflow initiated per user request. Dmitry's dosing/ISF/SMB/pump control remains unmodified; Boost remains disabled.

## V5 persisted key-schema metadata — CI deferred

- `8b151f60`: staged isolated `BoostV5PreferenceStorageSchema` with eight archived DoubleKey symbolic names, real persisted strings, factory defaults, minimum/maximum values and Simple Mode flags. These are **inert metadata**, not declarations in current `core/keys/DoubleKey.kt`.
- `d44664d7`: four unexecuted common tests covering unique identifiers, archived defaults, symbolic-vs-storage separation and parity with the passive symbolic catalogue.
- No tests or GitHub Actions started at the user's request. Actual key registration, migrations, Android UI, V1/V5 engines and device safety verification remain outstanding. Dmitry dosing/ISF/SMB is unchanged.

## Passive V5 stored-value format and range compatibility — tests deferred

- `8cdc4ab6`: offline `BoostV5StoredValueCompatibilityAudit` classifies supplied values by original persisted IDs as absent, valid, nonnumeric, nonfinite, out of range or unknown. It never clamps, writes or applies any value.
- `501dfc7c`: four staged regression scenarios for missing values, nominal stored defaults, malformed/out-of-range values and unknown settings identifiers.
- No tests, CI or APK build run per user request. These commits remain unverified, with live key registration, state persistence, V1/V5 APS engines and safety review outstanding.

## V5 unknown-key numeric isolation refinement (CI deferred)

- `37d79bec`: unknown persisted preference identifiers are no longer parsed into numeric values by the passive V5 stored-value audit; they remain `UNKNOWN_KEY` with null numeric data.
- `7b3db51a`: extended the corresponding staged regression case to check that an unknown setting's numeric result stays null.
- No tests or CI launched. The change is a read-only diagnostic refinement, not integration with the active Dmitry preference store or insulin dosing.

## Historical Boost V5 preference defaults — user-requested test checkpoint

- `e5fa9250`: adds read-only classification of current factory values, historical factory values, explicit overrides, absent and invalid stored values from a caller-supplied map. It performs no Preferences I/O.
- `9c4cc3ea`: stages four JVM regressions covering historical/current defaults, overrides and invalid/absent values.
- This documentation commit intentionally starts a complete Boost JVM/isolation/asset/fullDebug GitHub Actions run per user request. This checkpoint is unverified until that workflow completes, and it does not activate any dosing algorithm.

## V5 historical default recognition ordering (CI deferred)

- `2b75a984`: the passive historical-default inspector recognizes current and previous factory values before judging user-customized values against current range bounds. This is an inspection-only ordering refinement, not an authorization to apply out-of-range values to preferences.
- `4c33d138`: stages two additional JVM regression scenarios for the original cumulative-cap factory eras and a genuinely invalid custom value.
- No tests, workflow or APK build initiated per user request. Last confirmed passing workflow remains `40352751901`; this new stage is unverified. Neither live APS dose settings nor Dmitry's ISF/SMB/pump logic were changed.

## Source-verified Boost V5 production engine handoff (no tests)

- `b9ae4290`: new `V5_ENGINE_INTEGRATION_DEPENDENCIES_20261026.md` inventories actual source `OpenAPSBoostV5Plugin`, `DetermineBasalBoostV5`, `V5StateStore`, `BoostV5AutoConfigApply`, core profile and V1 delegation requirements. It sets safe sequencing and flags that the live engine, preference storage and DI wiring are absent from the current staged target.
- Only documentation updated, no CI/tests/APK triggered. Last passing workflow: `40352751901`. No change to Dmitry ISF/APS/pump behavior.

## Disconnected Boost V5 profile input adapter (tests deferred)

- `4a6f682f`: added `BoostV5ProfileInputBoundary` to isolate a documented subset of the original `OapsProfileBoost` fields (explicit mg/dL and basal U/h; target ordering, finite numeric checks, nonnegative cumulative steps). It only returns a passive snapshot, never builds a live dosing profile or activates an APS engine.
- `9effca13`: five unrun commonTest cases for passing snapshot, malformed numeric, target ordering, cumulative steps and basal value rejection.
- No CI or tests by user instruction. This remains preliminary structural inspection rather than a clinical safety check. Existing Dmitry ISF/SMB/pump behavior is unchanged.

## Disconnected V5 dynamic-ISF profile inputs — tests deferred

- `1eb96f3b`: added pure `BoostV5ProfileAdditionalInputs` to inspect a subset of archived OapsProfileBoost fields: variable sensitivity, TDD, divisor, velocity, insulin peak, boost flags and exercise subclass; rejects invalid input without constructing a live AAPS profile.
- `55f6bc35`: four commonTest regressions staged, not run. Input range checks are structural, not proof of clinical safety nor implementation of the dosing algorithm.
- No tests, CI or APK initiated per user request. Dmitry's live ISF ordering, SMB, APS invocation and pump interfaces untouched.

## Combined disconnected V5 profile boundary (CI deferred)

- `c1cb204a`: combines the previously staged base and dynamic-ISF input inspections into one offline result with independent failure reasons and no profile creation or dosing invocation.
- `3bb09d9c`: four unexecuted test scenarios cover both successful inspections and independently invalid input components.
- No tests, CI, APK, live key registration or clinical verification. Dmitry ISF/SMB/APS/pump logic remains untouched.

## V5 disconnected replay profile change comparison (CI deferred)

- `1b2ca187`: pure comparison of two independently validated V5 offline profile assemblies. It distinguishes unchanged profiles, base-field changes and supplementary-field changes, and declines comparison when either snapshot is invalid.
- `2adfe162`: three staged tests for unchanged, separately changed and unavailable profile snapshots. These are not executed yet.
- No tests/CI/APK triggered. This remains replay-preparation infrastructure, not a live Boost engine, and no Dmitry insulin/pump algorithm is changed.

## Offline V5 timestamped profile replay sequence (CI deferred)

- `b8262b98`: an inert timestamped profile-sequence inspection rejects empty streams, invalid profile frames and nonmonotonic timestamps; counts structural profile changes only, with no APS execution.
- `7030afe7`: four staged regression cases for empty, valid changed, duplicate-time and invalid-middle frames. Not executed.
- No tests/CI/APK per user request. This is offline replay preparation, not real V5 engine integration or clinical verification. Dmitry insulin control remains unchanged.

## V5 offline replay timestamp boundary hardening — CI deferred

- `d8412215`: replay inspection now rejects negative timestamps and checks frame chronology before examining profile validity. This enforces a deterministic reason for broken order in offline inputs; no live timekeeping or APS behavior is changed.
- `3048c208`: two additional staged regressions cover negative first timestamps and reversed order combined with an invalid profile. Tests not executed.
- No tests, CI, or APK build per user's standing request. Active Dmitry dosing remains untouched.

## V5 offline replay frame-count boundary (tests deferred)

- `11e288bb`: offline profile replay now declines sequences exceeding 10,000 frames before inspecting contents, preventing unbounded traversal of supplied replay lists. The 10,000-frame cap is an offline resource limit, **not** an APS clinical parameter.
- `d3f5a629`: added two unexecuted tests for 10,001-frame rejection and 10,000-frame acceptance.
- Tests/CI/APK not run on instruction. No active Dmitry dosing, ISF correction order, SMB or pump integration modified.

## V5 offline replay change-event extraction — CI deferred

- `307bc51c`: extracts timestamped base-versus-additional profile change events after requiring the entire offline replay sequence to validate. Invalid input yields no misleading partial event stream.
- `71b1f462`: three staged regression tests for unchanged profiles, distinct profile sections and invalid replay. Not executed.
- No tests, CI or APK run per standing user request. These diagnostics do not connect to production APS or change Dmitry insulin/pump behavior.

## V5 replay validated frame count — user-requested CI checkpoint

- `087d97e0`: the passive replay change event report now exposes the validated frame count only on successful whole-sequence validation. Invalid sequences yield zero validated frames and no events.
- `56dfba23`: staged assertions cover counts for both valid and rejected sequences.
- This documentation checkpoint deliberately triggers full GitHub Actions (Boost JVM tests, isolation, asset checks, fullDebug APK) per user instruction. No active Dmitry APS/ISF/SMB/pump logic was modified.

## Offline V5 replay change aggregate counters — CI deferred

- `f6bb2b7a`: adds passive aggregate counters over the previously validated offline profile-change events, distinguishing base, additional and simultaneous profile changes without counting the same frame twice.
- `74814267`: stages three regression cases for unchanged streams, simultaneous changes and invalid ordering (no partial counts).
- Tests, CI and APK intentionally not run for this intermediate block. Last confirmed passing workflow: `40356444530`. No active Dmitry APS, ISF, SMB, preference or pump behavior modified.

## V5 offline summary small refactor (CI deferred)

- `eeeb0137`: extracted independent base, additional, and simultaneous event counts into named locals in the existing inert offline replay summary, retaining the same classification behavior.
- No tests, CI or APK triggered; change not yet compiler-verified. This is not a live Boost engine port and no Dmitry dosing/ISF/SMB/pump code changed.

## Offline V5 persisted state clocks vs replay time (CI deferred)

- `a1aa0ea1`: added a pure audit comparing archived state persistence clock fields with an offline replay frame timestamp, reporting exact future-dated fields; it does not mutate locks or reset the meal state.
- `bc4682b6`: four staged tests for unset clocks, future primer time, invalid replay timestamp, and invalid stored state.
- No tests, GitHub Actions or APK triggered per standing request. Last green run `40356444530` predates this change. The real V5 APS dosing orchestrator, settings and DI integration remain pending; Dmitry's live ISF/SMB/pump code is untouched.

## V5 offline replay and persisted-state entry gate (CI deferred)

- `c261adbe`: introduces read-only `BoostV5OfflineReplayStateGate`, combining whole-profile-timeline validity with state-clock consistency at the **first** frame (prevents future state evidence being treated as valid at replay start). No actual state restore, APS execution or pump integration.
- `577872fe`: four unexecuted regression scenarios for accepted input, reversed replay times, future persisted clock and invalid snapshot.
- No tests, CI or APK build triggered per standing instruction. Last verified green workflow: `40356444530`. Live Dmitry ISF ordering and SMB/pump logic remain unchanged.

## V5 persisted replay entry adapter (CI deferred)

- `735fa556`: read-only adapter connects caller-supplied V5 stored-field maps to the offline replay entry check, explicitly rejecting corrupt, future-schema or cache-only state rather than silently resetting commit locks.
- `ef579379`: five staged regressions for legacy state, unknown schema, corrupt lock, future clock and reversed replay times. Not executed.
- No tests, CI, APK or live APS changes. Current Dmitry insulin correction and pump behavior are unaffected. Full Boost integration remains pending.

## First direct DetermineBasalBoostV5 orchestrator adaptation seam — CI deferred

- Compared source `DetermineBasalBoostV5.kt` on archived `3.4.2.6-Boost_V6_261026`: `V5Inputs` includes glucose deltas/history, IOB/maxIOB, **Boost-flavoured oref baseInsulinReq**, ML hypo/meal predictions, exercise/rescue/clock information, user knobs, and source state. `decide(V5Inputs, V5PersistedState): V5Decision` also depends on Phase-3 safety gates and V1 plugin fallback/override constraints.
- `ea7060fc`: added a strictly disabled `BoostV5OrchestratorPortContract` dependency seam. It never indicates dosing support, even if structural dependencies are all declared, and does not import the live APS types.
- `c16753db`: three staged regressions for missing dependencies, mandatory Boost oref sensitivity inheritance, and fail-closed behavior when nominal dependencies are all present.
- No tests, CI or APK per standing instruction. This is a **first interface-adaptation checkpoint, not the actual 60k-character orchestrator port**. Main `DetermineBasalBoostV5.decide` and live registration remain absent; no Dmitry insulin control changed.

## First source-shaped V5Inputs cycle envelope — tests deferred

- `26a28c02`: a pure, disconnected `BoostV5OrchestratorInputEnvelope.Cycle` records the original `DetermineBasalBoostV5.V5Inputs` fields, including explicit Boost-oref `baseInsulinReq`, glucose history, IOB, ML outputs, safety toggles, activity, dosing knobs, and cycle clocks. The original projected-IOB ML callback is represented only by a Boolean availability marker and cannot execute.
- `06e55206`: five staged input-shape tests for nominal, NaN ML risk, negative clock, invalid hour and missing delta history. These were not run.
- It does **not** port or invoke `decide()`, compute insulin, provide a live plugin or replace Dmitry dosing. No tests/CI/APK per standing request. Source default cap constants are intentionally not assumed; callers must explicitly provide them for offline inspection.

## V5 disabled orchestrator inputs + persisted-state entry checkpoint — CI requested

- `20c6f18f`: added a disconnected entry adapter for source-shaped V5 cycle inputs and persisted V5 field maps. It rejects malformed cycles, absent cycle clocks, unsupported persisted schema and future state clocks; both `canInvokeDecide` and `canDeliverInsulin` are always false.
- `20959abb`: added five regression cases around supported legacy state, unknown future state, future primer clock, unset cycle clock and corrupt glucose input.
- This documentation commit requests full Boost JVM/asset/isolation/fullDebug APK CI. No live APS or pump dosing code was modified, and this does not invoke the original DetermineBasalBoostV5.decide().

## Full source V5 orchestrator staged in isolated module (CI deferred)

- `702e6ed4`: copied the original `DetermineBasalBoostV5.kt` (source branch `3.4.2.6-Boost_V6_261026`, `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/`) directly into the *isolated* KMP `plugins/boost-port` commonMain package, retaining `V5Inputs`, `V5PersistedState`, `V5Decision`, and the complete `decide()` implementation. Removed only the original `javax.inject.Inject` / `javax.inject.Singleton` imports and the injection annotation from the class constructor, avoiding a DI binding to the active APS system. Existing pure V5 helper classes are already in that module.
- `55bd6ea8`: strengthened the static port isolation guard: asserts the copied orchestrator exists, has the expected entry signature, and contains no injection annotations/imports or active APS plugin markers.
- **Important:** The copied file contains insulin dose calculation logic but is not called or registered by the application. It is an *uncompiled, untested staged source copy* until the next explicit CI run. Do not interpret compilation or a green APK as approval to activate delivery. V1 flavoured oref precomputation, real key mappings, Preferences state, safety parity and human/device validation remain open.
- No tests/CI/APK triggered per instruction. Dmitry's current ISF order, SMB, APS/pump path was not edited.

## Full V5 orchestrator source-parity checkpoint — CI requested

- Rechecked the staged `DetermineBasalBoostV5.kt` imports: no DI or external imports; its source helper references resolve to existing isolated KMP helper files.
- Compared archived V6 source with staged KMP copies of `MealHypothesis.kt`, `AggressionBudget.kt`, `MealSignalScore.kt`, `SafetyGates.kt` and `MealActionMultiplier.kt`: **all five are byte-identical as returned by GitHub**.
- This checkpoint intentionally triggers CI for the previous complete orchestrator copy (`702e6ed4`) and enhanced isolation guard (`55bd6ea8`). CI success only establishes build/test success; it does not validate insulin safety or activate live dosing.

## Bulk archival of remaining V5 original components (CI deferred)

- Compared all 12 Kotlin files under archived `plugins/aps/.../openAPSBoostV5/` with isolated compiled Kotlin source. Nine source files have staged compiled counterparts. The three missing Android/Preferences components were copied **without modification** into a non-source `plugins/boost-port/legacy-source/openAPSBoostV5/*.kt.source` directory, so Gradle will not compile, register, or activate them.
- `62bcec9d`: original `BoostV5AutoConfigApply.kt` (32,666 chars); `b8d1158a`: original `V5StateStore.kt` (12,195 chars); `d48dc689`: original `OpenAPSBoostV5Plugin.kt` (68,936 chars). All three archived copies independently verified exact-string equality with source branch `3.4.2.6-Boost_V6_261026` using GitHub file fetches.
- Real remaining adaptation work: reconcile missing V5 keys to Dmitry keys, implement platform Preferences-backed state management without lock loss, adapt original Android plugin to the existing APS architecture, wire authentic Boost-flavoured oref/V1 semantics, offline parity regressions and independent clinical safety review before live dosing. No live changes or CI triggered in this archival block.

## Accelerated AutoConfigApply source adaptation (CI deferred)

- `1ee3de12`: stage eight original `DoubleKey` AutoConfig managed keys with **source-verified key strings and factory defaults** in an unregistered `BoostV5OfflineDoubleKey` enum. None are added to Dmitry's live `core/keys` Preferences.
- `c9375853`: copy the entire original `BoostV5AutoConfigApply.kt` into isolated KMP commonMain, substituting only the local offline key enum and KMP `kotlin.math.round` for JVM `Math.round`. Preserves the per-key default history, user override preservation, redrive and TBR raise guards.
- Pending: compilation, tests and source-to-source functional parity (not yet verified); Preferences writing, live plugin registration and insulin delivery remain absent. No CI or APK run this block.

## Source V5PersistedState schema bridge — CI deferred

- `35359038`: maps the **actual copied orchestrator's** V5PersistedState and MealHypothesisState to the existing strict portable stored-field codec and back, including committedInSession, session lock clocks, primer accumulators and chronological anchors. Does not access Preferences, JSON or mutate live APS state. Unrecognized fields fail closed.
- `1c319c97`: staged lock/primer round-trip and corrupted/unknown schema regression tests; **not yet run**. Ephemeral lastCycleScore and scoreReadySinceMs reset on restore, consistent with original store's exclusion from persisted JSON; mlNullStreakLastMs also excluded by original.
- Remaining: actual Android platform store / plugin integration, full Boost V1 input source, dosing equivalence offline validation, independent safety review. No CI/APK triggered. Working Dmitry ISF/SMB/pump unchanged.

## OpenAPSBoostV5Plugin: first offline RT telemetry extraction (CI deferred)

- Reviewed archived `OpenAPSBoostV5Plugin.kt`: it is an Android PluginBase/APS integration with direct Preferences, concurrency, Boost V1 runEngine(..., v5Active=true), `runShadow()` invoking `DetermineBasalBoostV5.decide()`, persisted state mutation and RT telemetry. Direct copying into commonMain is not safe or portable.
- `1363d2da`: migrated the read-only V5Decision→cycle telemetry field projection from `runShadow()` into the non-registered `V5ShadowTelemetryOffline` object. No RT mutation, hardware/pump access or live registration. In particular the resulting active-dosing flag is always false.
- `2963788f`: staged basic inert telemetry field regression test; not executed. This does not activate or compare the original V5 dosing algorithm. Source telemetry fields that depend on live Preferences, V1 RT, or hardware are intentionally not populated.
- Still pending: Android-specific plugin integration, authentic V1 `runEngine` input and fallback, concurrency and preference locking, independent clinical safety review. Tests/CI/APK not run this block.

## Source V5-to-V1 engine delegation seam (CI deferred)

- Source `OpenAPSBoostV5Plugin.invoke` selects `OpenAPSBoostPlugin.runEngine(initiator, tempBasalFallback, v5Active=true)` under config lock, so V5 relies on the full Boost V1 oref sensitivity, basal/prediction stack and override/fallback path; simply connecting `DetermineBasalBoostV5.decide` to normal AAPS would not reproduce it.
- `387137a0`: offline-only `V5V1DelegationOffline` records V1 input, fallback, persisted state and V5 engine prerequisites. It does not have an invocation callback, and can never enable live execution or delivery.
- `852b99d7`: three unexecuted regression cases for absent V1, missing V5 state and fully populated (still disabled) route.
- No actual active APS wiring, change to Dmitry ISF correction, pump access, CI or APK in this block.


## V5 offline migration regression gate — 2026-10-27

This checkpoint validates the accumulated V5 offline port before any further adapter work. It covers the staged, non-registered V5 orchestrator and state codec, AutoConfigApply adaptation, read-only telemetry and the V5→V1 prerequisite-delegation model (including tests added through `852b99d7`). The prior checkpoint used `[skip ci]`, leaving these regressions unexecuted.

CI for this commit must run `validate_ml_assets.py`, `check_port_isolation.py`, `:plugins:boost-port:jvmTest`, and `:app:assembleFullDebug` in the existing `boost-port-ci.yml`. A passing result only certifies those build and isolation checks, **not** behavior equivalence, Android device startup, or pump safety. The plugin remains hidden and disabled: no live integration, no change to Dmitry's early ISF correction, and no insulin delivery activation.


## Follow-up offline regression tranche — CI intentionally deferred

- `a7dfcc1d`: restored the original V5 AutoConfig managed-key min/max values in the non-registered `BoostV5OfflineDoubleKey` enum. Earlier GitHub Actions run `40366653289` successfully compiled and ran isolated Boost JVM tests, verified disabled/hidden status, and built fullDebug APK; it did **not** authorize live delivery.
- `db3c4c99`: stages a source-boundary table test for all eight offline V5 managed keys and a complete 16-combination truth table for the V5→V1 prerequisite contract. Every combination must remain unable to invoke live APS or deliver insulin.
- Per the latest user instruction, **do not run tests/CI yet**; this block is staged, not executed or verified. Commits use `[skip ci]` to avoid the normal push-triggered workflow. No active APS integration, Preferences writes, pump access or ISF ordering changes.


## V5 Android adapter preparation — tests deferred

- `a0d1e694`: added offline-only `V5OfflineAdapterGate`, validating prerequisite V1 engine/fallback availability, V5 engine/state availability, and a reversible portable V5 persisted-state snapshot. Invalid or missing inputs are rejected; this creates no Android plugin, preference writer, APS callback or delivery path.
- `25c36224`: staged four rejection-path regression tests for missing V1 input, absent fallback, unavailable V5 engine, and missing persisted state. They have **not been executed**.
- Source parity for actual `OpenAPSBoostV5Plugin.invoke`, concurrency, platform state store, and Boost-flavoured V1 engine inputs is not complete. This stage must not be described as active dosing integration. All commits carry `[skip ci]` at the user's request; CI/APK tests intentionally deferred.


## Offline Android state handoff checkpoint — CI deferred

- `77d41e99`: isolated immutable `V5OfflineStateHandoff` uses the existing strict V5 state codec; a missing/corrupt snapshot fails closed rather than silently restoring an unlocked meal session. Exports are snapshots only, with no preferences write or engine invocation.
- `40468b88`: staged rejection tests for absent, malformed, and nonexportable state; these tests were not executed.
- The existing live Android APS plugin and Dmitry ISF/pump code are untouched. The adapter remains unregistered and unable to deliver insulin. As requested, test execution and CI remain deferred; both commits use `[skip ci]`.


## Joined V5 state and V1 route boundary — CI deliberately deferred

- `48946936`: added `V5OfflineAndroidBoundary` composing the previously separate strict state restore, V5→V1 prerequisite route description, and offline preflight. Missing or corrupt state fails closed; no new unlocked meal session is constructed. The returned route always denies live APS invocation and insulin delivery.
- `3b438949`: staged three negative-path regression cases for missing and corrupt snapshots; no tests executed.
- This remains an offline commonMain inspection contract, **not** the Android `OpenAPSBoostV5Plugin`, actual source `runEngine`, preference-lock implementation, or real dosing pipeline. Live functionality remains outside the port. ` [skip ci]` commits intentionally suppress workflow execution under user instruction.


## Post-block verification requested — 2026-10-27

After the isolated V5 offline Android boundary block through `381a85fd`, the user requested tests. This checkpoint triggers the existing `Boost KMP port integration` push workflow on this branch, with ML asset validation, disabled/hidden isolation guard, `:plugins:boost-port:jvmTest`, and `:app:assembleFullDebug`. The preceding block had `[skip ci]` commits by request; this checkpoint deliberately does not.

A green run verifies compilation and the included tests only; it does not activate Boost or validate real-pump dosing.


## Batched disconnected V5 historical replay gate — CI deferred

- `3c7692a9`: added `V5OfflineBatchReplay` to inspect multiple archived V5 snapshots via the strict persisted-state and V5/V1 dependency gate. It stops at the first invalid frame and records the accepted prefix for diagnostics only; no replay callback, state writer, APS plugin or insulin delivery is introduced.
- `64619ed6`: staged empty-batch, malformed-first-frame and missing-engine rejection cases. These are not executed.
- Remaining real integration work is Android Preferences/state consistency under locking, authentic Boost V1 engine inputs, adapter wiring, and independent device/safety validation. Existing Dmitry ISF ordering is unchanged. Per instruction this block uses `[skip ci]`; no tests or APK compilation initiated.


## Offline V5 replay continuity audit — CI deferred

- `b6e81617`: adds disconnected chronological diagnostics for restored V5 persisted snapshots. It reports backwards age/commit clocks and disappearance of a session lock while the recorded last-commit anchor remains unchanged. Diagnostics never execute V1/V5 or access platform preferences, APS, or pumps.
- `4b474a3c`: stages four tests for invalid snapshots, backwards clock, lost session lock and a monotonic inspection sequence. **Not run**.
- The session-lock check is a conservative offline *diagnostic*, not a normative rule for the live algorithm: legitimate session release can require additional timing/delta evidence. Do not wire it into delivery without separate proof. User requested deferred CI; commits use `[skip ci]`. Existing Dmitry ISF sequencing and dosing remain untouched.


## Combined offline V5 replay audit — tests deferred

- `687f61cf`: added `V5OfflineReplayBatchAudit` to run the existing disconnected prerequisite and chronological-continuity checks as a single inspection. It reports the first rejected frame without executing an engine or writing preferences.
- `064e31a5`: staged three negative regression cases for empty batches, malformed persisted data and unavailable V1 prerequisites. These tests have **not been run**.
- This is a composite diagnostic only, not Android plugin integration or a verified live safety guard. The module remains disabled and hidden, without APS or pump callbacks; Dmitry ISF ordering is unchanged. CI remains deferred using `[skip ci]` at user request.


## First actual Android-specific V5StateStore JSON seam — CI deferred

- `6bab843a`: staged `AndroidV5LegacyJsonSnapshotAdapter` under **androidMain**, using `org.json.JSONObject` to read the real legacy V5StateStore field names and pass typed values to the existing strict portable snapshot codec. It also writes an *inert JSON string* for parity fixtures, with native JSON booleans, integers, longs and doubles. No Preferences access or writes, no cache, no `OpenAPSBoostV5Plugin` registration, and no APS/pump callbacks.
- Unlike prior commonMain-only diagnostics, this copies a concrete Android platform serialization seam while keeping the whole port hidden. Missing/invalid state rejects replay rather than silently producing an unlocked state. Original source `V5StateStore.load()` on corruption instead resets to IDLE, so the divergence is intentional for offline diagnostic safety and **must be reconciled before any live adapter**.
- Android compilation and parity tests for this new file have **not run**. Follow-up work: Android host tests and complete synchronized in-memory state-store adapter; authentic V1 source inputs and independent safety verification are still missing. `[skip ci]` per standing instruction.


## Android V5 isolated state-cache checkpoint and CI — 2026-10-27

- `fe715ab7`: Android-only synchronized offline cache added for legacy V5 JSON snapshots, without AAPS Preferences access, engine activation, or pump callbacks.
- `55c3bc13`: added four pure codec regressions covering missing mandatory keys, corrupted session lock, non-finite primer values, and locked-state round trip. No claims of Android runtime parity are made.
- This checkpoint intentionally triggers `boost-port-ci.yml` after the block, as requested: ML validation, isolation validation, common/JVM tests, and fullDebug APK compilation. Successful CI would not validate a real pump or insulin delivery.


## Post-green-CI source-field compatibility extension — CI deferred

- Last verified green run: `40371219139` on `41cb15b7`, including JVM tests, isolation checks and fullDebug APK build; it precedes the additional regression changes below.
- `3eecb1da`: expanded portable V5 persisted-state compatibility regressions for missing historical optional fields, rejection of negative/non-finite values, and exact session-lock/clock field preservation. These new cases are staged **but not executed**.
- Source boundary remains Android-only offline JSON parsing with an in-memory fixture cache. No Preferences persistence, source `OpenAPSBoostV5Plugin` activation, V1 engine execution, or pump delivery wiring. A full active Boost port is not yet complete; avoid treating the green APK as a dosing clearance.


## Android legacy JSON source-type boundary — CI deferred

- `d64070bf`: hardened `AndroidV5LegacyJsonSnapshotAdapter.inspect()` to accept the source `V5StateStore.save()` native JSON types only (`mealHypothesis`: string; `committedInSession`: boolean; other persisted fields: JSON numbers). Rejects stringified booleans/numbers instead of silently coercing potentially tampered input. The existing portable value codec still rejects nonfinite and invalid ranges.
- This changes only disconnected Android fixture inspection. It is not live Preferences storage or an enabled APS plugin; original source's corruption-reset behavior remains different. New Android code has **not been compiled or tested since this commit**. CI remains deferred via `[skip ci]`.


## Versioned Android offline fixture storage — CI deferred

- `2d988597`: introduced `AndroidV5OfflineRevisionStore` with synchronized in-process compare-and-replace semantics for legacy JSON snapshots. Invalid input and stale revisions do not overwrite the current fixture; reads return a revision for explicitly versioned offline clients. No AAPS Preferences, Android disk persistence, APS plugin registration or delivery connection.
- This is explicitly a test/diagnostic fixture layer, **not** durable `V5StateStore` implementation or proof of crash/restart consistency. Its Android compilation and tests have not been executed; CI remains deferred under `[skip ci]`.


## Android offline fixture lifecycle — CI deferred

- `cf7e8613`: corrected a malformed source comment in the Android JSON reader and clarified strict field validation; no live code path.
- `abdbba06`: added detached canonical JSON fixture export and compare-and-revision fixture clearing to `AndroidV5OfflineRevisionStore`. Stale readers cannot clear a newer offline snapshot; all operations remain synchronized and memory-only.
- This remains a disconnected Android test fixture (not Preferences, durable `V5StateStore`, or active `OpenAPSBoostV5Plugin`); Android compile/runtime tests have **not run** since these commits. Workflow intentionally skipped; real V1 engine inputs, plugin wiring and pump integration remain outstanding.


## Android offline revision integrity checkpoint — tests deferred

- `9cebf7d9`: a fixture replacement now serializes and then strictly re-parses its canonical V5 JSON before incrementing the offline revision. Invalid canonical output cannot alter the cached fixture or its revision.
- This is in-memory replay-fixture integrity only. It has no Android Preferences, live V5/V1 invocation, or pump integration. New code remains untested until a user-requested CI run; `[skip ci]` retained.


## Android JSON compile blocker and read-only source boundary — CI deferred

- `8925aa7e`: fixed literal escaped `\\n` characters embedded in comments in the Android V5 legacy JSON adapter. These had inadvertently swallowed `val fields = buildMap` and `BoostV5StateSnapshotCodec.decode(fields)` into line comments, a compile-blocking defect not covered by the previous JVM-only CI. This correction requires Android-target compilation to verify.
- `96d5ddd8`: added `AndroidV5OfflineSnapshotReader` as an explicit callback-based, **read-only** importer of historical V5 JSON. It discriminates failing reads, absent state, malformed values and valid canonical snapshots. The module does not acquire an AAPS Preferences instance, write state, create an unlocked IDLE state, invoke V1/V5, or register a dosing plugin.
- Next essential verification: compile Android target including this source file, not merely JVM common tests. Still missing real Android Preferences adapter and authentic V1 integration. CI not started, per user's standing instruction.


## Android Preferences integration dependency gap — 2026-10-27; CI deferred

- Compared archived `V5StateStore.kt.source` with target `core/keys/.../StringKey.kt` and `Preferences.kt`. Source reads `preferences.getBoostDosing(StringKey.ApsBoostV5State)` to bypass Simple Mode masking. The target branch's `StringKey` has no `ApsBoostV5State`, and the archived Boost-specific `getBoostDosing` dependency is not present in the target APS sources. Substituting an arbitrary preference key or ordinary `get` would be incorrect.
- `2937157c`: introduced `AndroidV5LegacyPreferenceImport`, a **read-only, disabled diagnostic boundary** requiring explicit caller attestation that the raw source is reviewed. It rejects unverified raw sources without calling them, routes parsing through the existing strict JSON reader, and has no Preferences object, write path, APS, or pump connection. An attestation flag alone is **not** a safe live authentication mechanism.
- **Unresolved:** migrate/register the real V5 state preference contract, review raw-mode retrieval semantics and restart behavior, implement genuine Android store synchronization, and adapt V1 dependencies. Do not consider this a live state store. No tests or CI triggered (`[skip ci]`).


## Legacy V5 preference key and read-only raw adapter — CI deferred

- Original Boost branch `3.4.2.6-Boost_V6_261026` declares `StringKey.ApsBoostV5State("boost_v5_state", "", defaultedBySM = true)`; the target branch lacks this entry. Its `Preferences` API does expose `getIfExists(StringNonPreferenceKey)`.
- `de6b3666`: reserved the **same underlying key string** as `StringNonKey.BoostV5OfflineLegacyState`, with export disabled and no sync spec, for an offline-only transition. This is deliberately a **non-preference key** rather than source's `StringKey`, so UI/defaulting/export semantics differ and require review prior to live use.
- `153e5aa0`: added `AndroidV5LegacyPreferencesReadOnly`, a passive importer through `Preferences.getIfExists` and the strict legacy JSON codec. Missing/invalid state does not default to IDLE and no write or pump capability exists. Nothing registers or invokes the bridge from the active APS plugin.
- **Caution**: registering an existing storage string in another key enum can affect preference classification, imports or ownership. Verify the storage/sync/import semantics and no duplicate key before deployment; a real V5StateStore requires separate atomic cache/persistence design. No tests/CI started (`[skip ci]`).


## Reserved legacy Preferences key verification checkpoint — CI requested

- `9e785120`: added common tests confirming the reserved historical `boost_v5_state` string, empty default, `exportable=false`, and no sync specification. These verify the key declaration, not end-to-end import behavior.
- This commit intentionally triggers the existing branch CI workflow on push, as requested after this block, to run isolation checks, JVM tests and fullDebug APK compilation.
- Android read-only adapter is still disconnected from the active APS; writes, crash/restart persistence, import behavior and actual dosing are unverified.


## Pre-connection CI hardening (2026-10-27)

- `846136e6`: CI now explicitly assembles `:plugins:boost-port:assemble` **before** `:app:assembleFullDebug`. The prior green APK only demonstrated that the disabled module did not break app compilation and did not independently prove compilation of Android-only Boost sources.
- This checkpoint triggers CI to verify the stronger Android-target build plus existing JVM tests, ML-asset checks, isolation guard, and complete APK build. Potential task-availability/compile failures must be investigated before any activation attempt.
- **No live connection authorized:** state-writing persistence, V1 engine route, source parity, simulator/fault-injection validation and independent medical-device safety review remain absent.


## KMP metadata compilation repair — CI recheck

- Run `40377138919` failed at `:plugins:boost-port:compileCommonMainKotlinMetadata`: JVM-only `@Volatile` in `HrFeedDarkTracker` and JVM-only `Map.toSortedMap()` in persisted-value audit.
- `0ecc9049`: removed unsupported annotations from the offline common HR tracker. This does not prove concurrency safety; its use must be confined to serialized/offline contexts until thread ownership is defined.
- `fe2d54d1`: changed persisted-value audit to portable `entries.sortedBy { it.key }`, retaining deterministic key order.
- This checkpoint intentionally triggers CI to revalidate common metadata, dedicated Android KMP compilation, JVM tests and fullDebug APK. No active APS or insulin delivery wiring is added.


## V5/V1 source parity gate — 2026-10-27

- `bd93b338`: added source-backed automated check of archived `OpenAPSBoostV5Plugin.invoke()` delegation under `configLock` to `OpenAPSBoostPlugin.runEngine(..., v5Active=true)` and the original cached `V5StateStore`/raw Boost preferences contract. The active KMP port still has **no** live V1 engine and must not infer V5 integration merely from V5 engine/unit tests.
- `67c114b1`: inserted this audit into GitHub Actions ahead of tests; scheduled on the next expressly requested CI run (both commits `[skip ci]`). Historical source contract verification is **not** behavioral-equivalence or clinical dosing validation.
- Remaining prerequisites: separately port V1 input gathering and basal/prediction engine, map its Android dependencies, implement reviewed durable V5 restore/write semantics with crash tests, and validate `configLock`/events/results with real source-derived fixtures entirely offline. No active APS registration or pump access authorized.


## V1/V5 dependency coverage — offline tranche

- `ab964823`: historical source-contract check now explicitly distinguishes migrated V1 helpers from the **absent** `OpenAPSBoostPlugin` basal/prediction engine. Prevents incorrectly counting helper portability as a completed V1 engine port; source-only checks do not imply functional equivalence.
- `38a366de`: exhaustive 16-combination offline V5→V1 dependency/fallback test ensures the diagnostic route never grants live invocation or insulin delivery. This is not a substitute for actual V1 engine replay or pump-independent dosing safety tests.
- No CI run triggered (`[skip ci]`). No APS/pump plugin registration or live state writes. Remaining key integration dependencies are unchanged.


## Full V1 original source recovered for migration — 2026-10-27

- `c69cf4de`: archived the complete 201,196-character, 2,929-line original `OpenAPSBoostPlugin.kt` from branch `3.4.2.6-Boost_V6_261026` as `plugins/boost-port/legacy-source/openAPSBoost/OpenAPSBoostPlugin.kt.source`. The `.source` extension keeps Android-specific source out of KMP compilation.
- This source owns `runEngine(initiator, tempBasalFallback, v5Active)` and calls V5 `runShadow(...)`; it imports Android context/UI, APS results, glucose/sensitivity calculators, constraints, IOB/COB, persistence, and other live dependencies. It cannot be naively copied to KMP commonMain. Reference archive now permits precise stepwise extraction of pure components and source-level parity reviews.
- This is a substantial prerequisite but **not** a functional V1 port. No code is registered for live APS delivery. CI not triggered (`[skip ci]`).
