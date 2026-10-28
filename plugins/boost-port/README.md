# Boost Kotlin Multiplatform port

Working branch: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.

The portable V1 → V5 → V6 calculation pipeline is connected to the standard
AndroidAPS APSResult boundary. **Boost V6 is selectable in Configuration → APS**
in an APS build; selecting it uses the standard Loop and pump constraints.
It is not forced on or made the default algorithm. The earlier General inspection
plugin remains disabled.

This is a partial semantic port, not a byte-for-byte replacement of Dmitry's
algorithm. Dmitry's existing Autosens/ISF correction order is retained.
Boost V2 and V7 remain excluded.

## Integrated components

- Full V1/V5 calculation, final V6 override, ML model loading, replay/state
  persistence and guarded APS result mapping.
- Profile/IOB/meal/TDD preparation and shared Dynamic CR integration.
- Night/sleep/recovery/premeal policies, source settings and English/Russian UI.
- APS-owned phone steps, Health Connect HR and step history, persisted daily
  step maxima and diagnostic activity baseline.
- Boost 6.2 automatic configuration, value ownership, weekly switch/primer-route
  resolution and personalized tranche history; bounded initial repair through Nightscout.
- Boost 6.2 device-age telemetry and daily insulin-need diagnostics.
- The source live 14-day TBR gate for aggressive early confirmation, shared
  with the composed and velocity-budget floors.
- Native CGM history/deltas, sensor-cadence sleep telemetry and an opt-in global
  Loop cadence setting; five-minute IOB/COB and autosens buckets are retained.

The APS adapter issues no pump-delivery commands; the existing Loop owns that
boundary. Activity-load and insulin-need diagnostics do not change a dosing
candidate. Source arithmetic is isolated from Android readers, storage and
presentation dependencies.

The latest [6.2 eligibility transfer](APS_ELIGIBILITY_62_20261028.md) passed
**1422 Boost JVM tests and 137 Android host/application tests**, zero failures,
errors or skips, plus Boost KMP assembly and fullDebug APK compilation in
[CI 40713999416](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40713999416).
Boost no longer reads the retired start/end clocks: tiers follow source 6.2
night/sleep/learned-lie-in/genuine-wake conditions. Standard Loop constraints,
target StepFeed hardening and raw import/export keys are preserved.

The [source inventory](SOURCE_TARGET_INVENTORY_20261028.md) accounts for all 39
implementation units and four matching model/metadata assets. It records remaining
ISF/VWA-TDD live shadow, guard-curve trace and source version-metadata gaps; mapping
all files is not a claim of complete diagnostic or clinical parity.

The earlier [sleep checkpoint](APS_SLEEP_FRESHNESS_62_20261028.md) records 1414 JVM
and 137 Android tests. The [activation checkpoint](APS_ACTIVATION_20261028.md)
records 1401 JVM, 135 host and two app graph/catalog tests at its own revisions.

## Earlier verified checkpoint

The final implementation passed **1399 Boost JVM tests and 73 Android host tests**
with zero failures, errors or skips, and fullDebug APK compilation.

- [CI 40675993285](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40675993285) at
  `285e9547844f04ae3a460db9562a3e944658a8ff`: 1399 JVM tests, 46 autosens
  host tests and Boost KMP module assembly passed.
- [CI 40679752693](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40679752693) at
  `6f6a6dc265c6eeeaae6bf7c318eea73e13c05a28`: 13 post-calculation tests,
  five Loop cadence tests, nine SMB glucose-status tests and fullDebug APK passed.
  Its exact-baseline diff guard verified that production sources were unchanged
  from the first checkpoint; only two host-test files and the validation workflow
  differed. XML reports confirm native/default selection, cancellation recovery,
  minute-feed delta units and all four elapsed-time V5 score-streak scenarios.

The working-branch transfer preserves these tested production and test sources,
records the results here, and restores the complete CI workflow for future changes.
The temporary coverage-reuse workflow is confined to validation history.

See [auto-config scope](APS_AUTOCONFIG_62_20261028.md) and
[native cadence scope](APS_NATIVE_CADENCE_20261028.md) for the two bulk transfers.

See [APS integration](APS_INTEGRATION_20261027.md), the
[live early-confirm gate](APS_EARLY_CONFIRM_TBR_20261028.md),
[insulin-need watch](APS_INSULIN_NEED_20261028.md),
[step history](APS_STEP_HISTORY_20261028.md),
[bounded history repair](APS_HISTORY_BACKFILL_20261028.md) and
[device age](APS_DEVICE_AGE_20261028.md) reports for scope and design details.
Older reports record their own historical validation state; they must not be
read as proof that later changes passed.

## Remaining work

1. Verify selected Boost V6 calculations and standard Loop interaction on a device.
2. Validate native-cadence operation with real CGM/pump timing. Host/device/Apple
   tests remain distinct from JVM tests and APK builds.
3. Complete remaining source diagnostic parity: live ISF/VWA-TDD shadows, guard
   curve-name breadcrumbs and source-specific Boost version upload metadata.

The base archive remains the 114 original source blobs pinned in
[SOURCE_MANIFEST.md](SOURCE_MANIFEST.md); it is not rewritten as later features
are adapted. Source Kotlin files there retain their `.source` suffix and are
not production source sets. Later 6.2 transfers name their source commits in
their own reports. Reference/oracle classes belong only to test source sets.

