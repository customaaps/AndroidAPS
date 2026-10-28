# Boost Kotlin Multiplatform port

Working branch: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.

The portable V1 → V5 → V6 calculation pipeline is connected to the standard
AndroidAPS APSResult boundary. **The Boost APS plugin remains hidden and
forcibly disabled.** Successful compilation and unit tests do not remove that
gate. The earlier General inspection plugin also remains disabled.

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
- V5 automatic configuration, weekly re-derivation and bounded initial history
  repair through Nightscout.
- Boost 6.2 device-age telemetry and daily insulin-need diagnostics.
- The source live 14-day TBR gate for aggressive early confirmation, shared
  with the composed and velocity-budget floors.

The APS adapter issues no pump-delivery commands; the existing Loop owns that
boundary. Activity-load and insulin-need diagnostics do not change a dosing
candidate. Source arithmetic is isolated from Android readers, storage and
presentation dependencies.

## Verified checkpoint

CI [40671066881](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40671066881)
completed successfully at code commit `da27329a6ac9b1c541391ee0164f7528d842e61a`:
1256 JVM tests, zero failures/errors/skips; Boost module assembly and fullDebug
APK compilation passed. XML reports confirm all six source gate cases and all
ten live-owner cases, including cancellation. The documentation-only commit
recording this result changes no production or test source.

See [APS integration](APS_INTEGRATION_20261027.md), the
[live early-confirm gate](APS_EARLY_CONFIRM_TBR_20261028.md),
[insulin-need watch](APS_INSULIN_NEED_20261028.md),
[step history](APS_STEP_HISTORY_20261028.md),
[bounded history repair](APS_HISTORY_BACKFILL_20261028.md) and
[device age](APS_DEVICE_AGE_20261028.md) reports for scope and design details.
Older reports record their own historical validation state; they must not be
read as proof that later changes passed.

## Remaining work

1. Port and compare the remaining Boost 6.2 auto-config ownership, loosening
   guards, weekly switch/primer-route resolution and personalized tranche
   threshold/evidence pipeline.
2. Integrate native CGM cadence and the associated global Loop scheduling.
3. Complete the remaining Android lifecycle, import/export, notification and
   device-level scenarios. Host/device/Apple tests are distinct from JVM tests
   and APK compilation.
4. Reconcile the final source-to-target inventory and complete validation
   before considering any change to the activation gate.

The base archive remains the 114 original source blobs pinned in
[SOURCE_MANIFEST.md](SOURCE_MANIFEST.md); it is not rewritten as later features
are adapted. Source Kotlin files there retain their `.source` suffix and are
not production source sets. Later 6.2 transfers name their source commits in
their own reports. Reference/oracle classes belong only to test source sets.
