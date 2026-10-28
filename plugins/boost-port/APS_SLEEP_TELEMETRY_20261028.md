# Boost sleep/HR result and persistence integration — 2026-10-28

The end-of-cycle sleep update now populates the 15 original optional RT fields
for sleep state, entered-at time, entry reason, learned schedule/session count,
learned HR baselines, latest/5-minute/15-minute HR, 15-minute reading count and
resolved HR source diagnostics. Names and types match original RT Git blob
`ac553e37db44d2a61c098405bba71dc33a81f7a5` from source commit
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. That full file is archived under
`port-source/RT.kt.source`; the reference generator verifies its Git blob hash.

## Captured-cycle semantics

Dosing/activity/V5 still consume the previous sleep snapshot. After V1/V5/V6
finishes, the sleep owner updates its state/history and the result reports the
new state. Learned values use the aggregate computed BEFORE recording this
cycle's transition, as in the original upload block: a just-closed sleep session
first contributes to the next cycle's aggregate.

Telemetry reuses copied rows from the detector's single captured 30-minute HR
query. It does not perform another read or mutate the candidate's dosing values,
predictions, profile or canonical reason. Averages remain duration-weighted,
use strict lower time cutoffs, and round to one decimal. An invalid newest row
does not cause an older row to be reported as latest. Empty data stays absent
for nullable values, with a zero valid-record count and `none` source state.

The established port hardening remains: future timestamps are not a fresh HR
source. The owner already bounds its query at the captured cycle timestamp.
This differs deliberately from the original resolver's unbounded negative-age
freshness predicate. Source parity tests use captured rows at or before now;
a separate test keeps the existing future-timestamp hardening explicit.
Nonfinite valid HR telemetry rejects the calculation instead of poisoning the
later device-status JSON. The shared JSON diagnostic names the three HR fields.

## Database and wire compatibility

The database entity enum and APS converters previously had no BOOST case and
would throw `Unsupported` while storing a Boost result. BOOST now uses the
standard SMB-shaped glucose/profile context and the existing RT JSON column.
The enum's converter stores its name in an existing TEXT column: no SQL column,
table or schema-version change is required. Existing algorithms and UNKNOWN's
explicit rejection retain their behavior.

Unset telemetry fields are omitted by the existing JSON encoding. Old result
JSON remains readable, RT copy/restore retains the added fields, and the standard
APS JSON, cloning and Nightscout suggested/enacted transport retain them.
There is no new upload endpoint and no network transmission in the tests.

## Verification

The JVM-only oracle extracts the original upload assignments, independent HR
source resolver and original RT declarations. It uses the original reference
sleep average and rounding implementations. A 108-context matrix compares the
15 values across sleep states, entered-at presence, learned history, empty,
invalid, weighted, zero-duration, stale and cutoff HR inputs.

Additional checks cover captured mutable-row isolation, no additional queries,
learning's one-cycle ordering, unchanged dosing/reason, old JSON, copies,
nonfinite HR diagnostics, actual SQLite BOOST storage, context reconstruction,
and Nightscout remote mapping. The plugin remains hidden and forcibly disabled.
Device/Apple execution and signed remote release validation are not claimed.

## Still pending

Phone/Health Connect ingestion, detected sensor-cadence wiring, HR dark-feed
notification orchestration, recovery and premeal/meal-policy owners, settings
UI, the remaining activity/V5/V6 telemetry and lifecycle scenarios are still
required before enabling Boost.

Passed local tests: 1185 Boost JVM tests, 8 APS persistence converter tests,
3 real-SQLite result tests, 10 DetermineBasalResult host tests, 7 Boost APS host
tests and 2 device-status/Nightscout host tests (1215 total), no failures, errors
or skips. A Kotlin incremental-compilation cache error was bypassed for this
verification with `-Pkotlin.incremental=false`; no dependency/lock changes.

Boost module assemble and full-debug phone/Wear builds passed, including iOS
test compilation (not execution). Both APK v2 signatures verify. Phone DEX
contains BoostSleepTelemetry and the APS factory/plugin, all four model assets
match the archive, and JVM oracle classes are absent.

Built APKs (before committing this checkpoint):

- `app/build/outputs/apk/full/debug/app-full-debug.apk`: 260073049 bytes, SHA-256 `2c826d2d99838f1c737aa2e4d0407fdce90537e5413a1ad1bbf6458d6424bf1a`.

- `wear/build/outputs/apk/full/debug/wear-full-debug.apk`: 72050385 bytes, SHA-256 `db14d1808ec1fdfd1f4c998d316e5c560962d0caa7978d5ee77c1af870b1272b`.
