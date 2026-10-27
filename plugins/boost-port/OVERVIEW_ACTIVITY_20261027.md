# Overview values and activity/history checkpoint

This is an intermediate port checkpoint on `nigthscout-dev-boost-port-20261026`,
following `a1e83f557152e7adac424bb5241f8c1add462670`. Source remains
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`; Boost V2 is excluded.

## Approved Overview layout

The equipment card now places compact cannula/reservoir/sensor/battery status
on the left and ISF/CR on the right, e.g. `ISF 5.6 → 4.6`, `CR 10.0 → 8.0`.
Existing status visibility settings, warnings, expansion and action permissions
are retained. A narrow pane or a large accessibility font stacks the sections
instead of shrinking or cutting off values. The three Overview layouts use the
same component. The former sensitivity chip retains Autosens percentages without
duplicating the ISF pair. Tapping the new values opens the sensitivity details.

AMA, SMB and AutoISF capture the original profile baseline and the actual `sens`
and CR consumed by the engine in optional RT fields. They survive serialization,
cloning and device-status transport. Older results without these fields remain
readable. The UI prefers the same-cycle snapshot over the current profile,
converts ISF to the configured glucose units, and never runs Dynamic CR itself.
The baseline CR fallback reads the profile schedule directly. Equal formatted
values show once, without an arrow; nonfinite/nonpositive metadata is ignored.
English/Russian labels retain ISF/CR abbreviations.

## Source activity and actual target history

The complete source `HrActivityCalculator`, `SleepStateDetector`,
`SleepHistoryTracker`, `DailyStepHistoryTracker` and `MealTimeLearner` are now
portable common code. `tools/activity_sources.py` verifies their mechanical
namespace/JSON/platform-math adaptations; the 114-blob snapshot remains intact.
The source's original seven relevant test classes are migrated to JVM tests.
Additional tests compare the complete sleep state machine through two days,
feed outages and restart, plus all history JSON defaults against original
JVM source classes. A small kotlinx.serialization boundary replaces org.json
only in portable code; source oracle dependencies stay test-only. It preserves
the source optString behavior for an explicit JSON null (the string `"null"`).

`BoostTargetHistoryReader` reads target PersistenceLayer BG/SMB data at one
captured timestamp, retaining the source 60/45-minute nadirs, braking product,
12-hour SMB lookback, 60-minute raw volume and last-SMB age. Target BS amounts
already use canonical IU. Unlike the source query-error fallback (0/720), a
query failure propagates so an integration caller cannot mistake missing
delivered-insulin history for zero delivery. Empty successful history retains
the source first-use values.

`BoostTargetCycleAssembler` joins that history, the target TDD reader, shared
Dynamic CR and the complete Boost profile adapter into one engine input. It
retains incoming SMB/flat-BG flags and hard-constrained values. It does not
register, enable or schedule a dosing plugin.

## Validation state at preliminary push

Passed before this checkpoint: 2 RT storage/compatibility tests, 23 targeted
shared-CR/sensitivity tests, 11 targeted UI tests and the full 340-test APS host
suite. Source/archive/model/isolation and whitespace checks pass. The final
expanded Boost suite, UI render export and APK assembly are still being checked
at the time of this preliminary save; no completed-build claim is made here.

Live Boost remains incomplete: activity/settings collection, source-mode and
lifecycle orchestration, APS registration/invocation and downstream delivery
integration still need completion. The inspection plugin remains hidden,
GENERAL and forcibly disabled. Recorded-patient/device/ART validation has not
been performed.

## Activity preparation follow-up

The full source `calculateBoostActivity` flow is now common code, including the
clock/night/sleep/lie-in guards, live-step availability, inactivity eligibility,
step exercise detection and HR refinement. Typed settings and previous-cycle
snapshots replace legacy preference/static-service reads. Real target SC/HR
queries remain explicit suspend calls anchored at the supplied cycle timestamp.
Merged step windows are returned with the result so profile assembly can reuse
the same readings. `assembleWithActivity` performs this stage before profile/ISF
assembly and requires its owner to resolve recovery/meal context in between.
There is still no automatic application owner or APS registration.

Tests compare the extracted source flow across 96 clock/feed/HR/target contexts
and separately cover absent feeds, sleep/night suppression, exercise protection
outside the Boost window, user percentage overrides, elevated HR with few steps
and coroutine cancellation. The full source Wear step bridge is also ported,
with its original tests for slot deduplication and completed-day history.

The preliminary commit's GitHub KMP workflow exposed JVM API calls in common
code that JVM/Android compilation had accepted. `removeIf`/`getOrDefault` now use
portable collection operations. Activity diagnostic formatting uses an explicit
expect/actual boundary: Android/JVM retain original String.format semantics;
iOS uses the application's native NumberFormat, affecting diagnostic text only.
No Apple-device formatting parity is claimed. The static guard checks these API
leaks, and full `:plugins:boost-port:assemble` is included in local verification.

The follow-up passed **1,129 Boost JVM tests**, with zero failures/errors/skips,
plus `:plugins:boost-port:assemble` (common/iOS metadata and iOS test compilation
included on this host) and `:app:assembleFullDebug`. These compilation checks do
not execute tests on an Apple device. The earlier 2 RT, 23 implementation, 11 UI
and 340 APS host tests passed on the unchanged related application code. The
new UI was also rendered under Robolectric and checked for text overflow.

Cloud checks must use writable tooling directories: `KONAN_DATA_DIR` under
`/workspace/.konan` for KMP assembly and Java `user.home` under
`/workspace/.java-home` for Robolectric's download lock/cache. These are saved in
the cloud activation/setup instructions; system HOME is not changed.
