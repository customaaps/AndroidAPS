# Boost policies, data acquisition and settings checkpoint

Target: `nigthscout-dev-boost-port-20261026`. Original source remains
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. V2/V7 are excluded. The APS
plugin remains hidden and forcibly disabled; this checkpoint does not enable dosing.

## Calculation owners

The sequential APS owner now applies source post-exercise recovery, owned activity
temporary targets, rebound cancellation, premeal shadow/live target policy and
meal-time learning. Recovery never overwrites an active user temporary target;
cancellation requires its exact owned timestamp and ACTIVITY reason. Source order
is retained: rebound cancellation clears the V1 temporary-target flag while the
same-cycle activity targets and ISF inputs remain those already captured.
Meal learning uses the prior sleep state, excludes night meals and commits only
after a successful decision. Invalid persisted history rejects the cycle.

The tight-ramp trial retains a persisted UUID seed and source daily assignment.
V5 composed floors/velocity budgets use the source 14-day gate: at least 1000
readings, strictly below 2% under 63 mg/dL and 3.5% under 70 mg/dL. Valid
percentages cache for one hour; missing history never enables the floor.
Close/import cancels the active suspend cycle, prevents subsequent preference
writes and releases the data lease once. Persistence failure does not advance
owner state. Recovery audit notes have English/Russian resources.

## Android acquisition

APS-owned leases consume the phone step counter using source bucket/day-ledger
rules and supply real 5/15/30/60-minute and current-day counts. Permission denial
or an absent sensor remains an unavailable feed. The last lease unregisters the
listener. Health Connect ingestion reads heart rate with an 18-hour overlap;
permissions are requested only through the explicit settings action.

Health Connect retains original sample cadence, including distinct samples in
the same minute, source timestamp deduplication and existing database-minute
precedence. Pagination must finish before persistence. Failed writes can retry;
deduplication state advances only after persistence succeeds. Poll work belongs
to the lease and is cancelled on close/disable. The read-only adapter's existing
passive default policy remains available to diagnostics.

A source HR dark-feed episode yields a localized notification only after a valid,
current APS result is published. Sensor/Health Connect permission UI and its
rationale are explicit Android activities, never launched by calculations.

## Settings

67 typed UI aliases retain the original 90-key storage names/defaults/export
rules, use source editor bounds and do not expose auto-managed state. Provenance
and source bounds are recorded in `port-source/aps-ui-settings.json`. English and
Russian labels cover the new groups and controls. Night BG offset is explicitly
edited in mg/dL so small offsets cannot be mistaken for mmol/L by unit guessing.
Shared SMB/UAM/basal/IOB controls are available on the APS settings screen.

## Validation and limits

1205 Boost JVM tests, 10 selected APS/settings host tests and 36 selected Overview
host tests passed with no failures, errors or skips. Tests cover recovery ownership,
rebound thresholds, premeal gates/learning, persistence failures, cancellation,
floor history/cache, trial assignment, phone windows and HR ingestion/retry/cadence.
The Boost module assembled, including native metadata and iOS test compilation.
The 114 archived source blobs, 90 key defaults, static isolation, V1/V5 source
contract and Russian resource audits pass. These source audits alone do not prove
Android lifecycle or whole-policy parity. Apple tests and device tests were not run.

Remaining integration work is listed in [the APS integration checkpoint](APS_INTEGRATION_20261027.md).
The [later auto-configuration checkpoint](APS_AUTOCONFIG_20261028.md) connects
V5 automatic derivation. Source native CGM cadence, Health Connect step-history/
baseline owners and remaining runtime telemetry/lifecycle coverage still precede
enabling the APS plugin. Clinical/device validation is not claimed.

## Full-debug Android artifacts

Phone and Wear assembled successfully together in 5m 26s. Both APK v2 signatures
verify. Phone DEX contains the new recovery, meal, acquisition and permissions
classes; the four source Boost model assets are byte-identical to the archive.
The reference test package is absent from phone DEX.

- `app/build/outputs/apk/full/debug/app-full-debug.apk`: 252710305 bytes; SHA-256 `79514175fcb34dfa45f9e070f3f6c11e20219ab6e87e0c53475146a649d166b4`.
- `wear/build/outputs/apk/full/debug/wear-full-debug.apk`: 73600501 bytes; SHA-256 `e8d1aa8615bfba1076de016d0336cfcaa1cecec978f9ca56b47d43e2e693ace3`.
