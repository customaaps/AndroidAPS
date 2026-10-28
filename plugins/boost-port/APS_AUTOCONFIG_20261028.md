# Boost V5 automatic configuration checkpoint

Target: `nigthscout-dev-boost-port-20261026`. Frozen source remains
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. V2/V7 stay excluded. The APS
plugin remains hidden and forcibly disabled.

## Connected owner

The sequential APS owner now performs source onboarding from 14 days of history
and scheduled re-derivation from 28 days, at seven-day intervals. It uses real TDD,
normal/SMB boluses and CGM history, with the source seven-day/1500-reading minimum.
TDD collection is anchored to the captured cycle time. Constrained maxIOB and
max-bolus values are passed in canonical pump units; invalid limits are rejected
before state writes. Older replay callers without the new bolus limit retain
calculation behavior without running this owner.

The source per-key resolved flags, legacy done-flag migration and both schema
versions are preserved. Fifteen additional source state keys are owned by the APS
plugin for import/export (12 resolved flags and three schema/cadence keys). The
original 90-key metadata contract is unchanged. User-tuned knobs are retained;
historical factory defaults remain eligible. Source TBR/severe-low raise guards,
computed cumulative/primer caps, deadbands, repeated confirmation and the 25%
per-evaluation bound run through the existing source-adapted pure functions.
Unapplied driver movement remains in the baseline for the next scheduled run.

Configuration is serialized after the persisted calculation, so new settings
first affect the next cycle. The original uses an asynchronous executor; this
port uses the existing cancellable owner rather than a separate unowned worker.
All configuration values, resolution flags, JSON ledgers, clocks and audit history
are staged, then committed in one KeyValueStore edit. Query/commit exceptions do
not partially advance configuration. A detected settings change during history
reads aborts the staged batch. The underlying preference API has no compare-and-set
primitive; this check does not claim atomic exclusion of independent UI writers.
Stop/import cancellation prevents subsequent owner writes.

Corrupt/nonfinite ledger data rejects configuration instead of silently resetting
it. Rolling history is limited to ten entries and records the actual previous
value (the original helper's operativeValue already contains the new value).
The source autordv breadcrumb is carried into RT reasons/Nightscout on subsequent
cycles. Initial configuration and changes/held suggestions produce a localized
English/Russian informational notification after current-result publication;
stale/imported results cannot publish it.

## Source comparison and validation

The JVM reference generator compiles hash-pinned original BoostV5AutoConfig and
BoostV5AutoConfigApply bodies with only package/key bindings adapted. Source key
names/defaults and bounds come from frozen metadata; primer constants are extracted
from the original engine. Reference classes belong only to jvmTest.
Comparisons cover 1440 onboarding cases and 324 successive re-derivation steps,
including user overrides, historical defaults, low-glucose thresholds, baselines,
confirmation, clipping, operative caps and updated ledgers. The adapted human
primer rationale is excluded from suggestion equality; numeric/boolean settings,
classification reasons and persistence outputs are compared. This comparison
exposed and fixed a previous port discrepancy: Kotlin round used ties-to-even,
whereas source Math.round breaks half ties toward positive infinity (for example,
a clipped 3.125 cap must become 3.13, not 3.12). The common implementation now uses
roundToLong to preserve source rounding, including signed diagnostic deltas.

Owner/boundary tests additionally cover thin-history retries, migration, weekly
restart, clipped residual movement, query/write failures, malformed ledgers,
concurrent user edits, cancellation and localized/stale notification publication.
Notification host tests explicitly return Unit so JUnit executes them rather than
silently skipping methods inferred to return NotificationHandle. The prior HR
warning test had the same issue and is corrected as part of this checkpoint.
1218 Boost JVM tests and 13 APS/settings Android host tests passed with zero
failures, errors or skips. The report explicitly includes the three notification
test names. The Boost module assembled with common/native metadata and iOS test
compilation. Static source/archive/key/isolation/model audits and the Russian
resource audit pass (5540 localized resources plus 23 safe inherited resources).
Phone and Wear full-debug APKs assembled; both APK v2 signatures verify. Phone
DEX includes the auto-config owner/notification and excludes the JVM reference
package. All four source model assets remain byte-identical.

## Remaining work

Automatic V5 derivation is connected. Full native CGM cadence/global Loop wiring,
Health Connect step-history/baseline owners, bounded source history-gap repair
orchestration and remaining activity/V5/V6 telemetry/lifecycle scenarios are still
open. Device/clinical validation and Apple test execution are not claimed.

## Android artifact hashes

- `app/build/outputs/apk/full/debug/app-full-debug.apk`: 252711205 bytes; SHA-256 `9c3762b99def88cfebe47d66356e292e49436da27939065c25161dee0cd1363d`.
- `wear/build/outputs/apk/full/debug/wear-full-debug.apk`: 73600501 bytes; SHA-256 `2502b18af9f36c2d9fbb79ae9f361f878f54013cb9994f989f34dbcc67ea7d1c`.
