# Boost V1/V5/V6 — remaining integration map (26 October 2026)

This is a **source-to-target gap inventory**, not an implementation-complete claim.
Reference source: `agniabakush/AndroidAPS:dev/plugins/boost-port/legacy/`.
Reference target: `agniabakush/AAPS-3.4.2.3_Dmitry:nigthscout-dev-boost-port-20261026`.
The source inventory in `SOURCE_MANIFEST.md` includes 39 Kotlin sources,
four ML JSON assets and 71 Kotlin tests; target currently contains 142 files
under `plugins/boost-port/`, many of which are isolated read-only helpers
and JVM regression tests rather than the original engine implementation.

## Unported live engine boundaries (not safe to activate)

| Source component | Original responsibility | What is available in target | Remaining dependency |
|---|---|---|---|
| `openAPSBoost/OpenAPSBoostPlugin.kt` | V1 APS algorithm, live profile/IOB/TDD, UI, events, preferences | Inspection-only GENERAL plugin, pure telemetry helpers | APS PluginBase implementation, plugin registration, profile and insulin data interfaces, event lifecycle; no live port |
| `openAPSBoost/DetermineBasalBoost.kt` | V1 basal/SMB calculation | Various pure safety/diagnostic inspections | Full algorithm conversion and dosing parity/safety validation |
| `openAPSBoostV5/OpenAPSBoostV5Plugin.kt` | Active V5/V6 observe-confirm-commit plugin and coordination with V1 engine | Offline meal state/score and ML audits | APS registration, dependency injection, active-plugin selection, state transitions, settings and RT/UI integration |
| `openAPSBoostV5/DetermineBasalBoostV5.kt` | V5 action/safety-gate pipeline | Passive tranche/state/math audits | End-to-end engine adaptation, ordered gates, pump constraints, therapeutic safety review |
| `openAPSBoostV5/V5StateStore.kt` | Atomic persisted JSON plus process-local cache | `BoostV5StateSnapshotCodec`, `BoostV5StateContinuityDiagnostics` | Actual AAPS Preferences adapter, cache synchronization, lifecycle reset, legacy JSON migration |
| `openAPSBoostV5/BoostV5AutoConfigApply.kt` | Writes/migrates settings | Passive `BoostV5SchemaMigrationAudit` / resolution audit | Settings key mapping, migration I/O, rollback and preference interaction |
| `openAPSBoost/BoostMlFeatureBuilder.kt` | ML ring-buffer JSON persistence and live feature vectors | Passive ring, metadata, feature-order, restore diagnostics | JSON persistence adapter and ingestion; parity across device restarts |
| `openAPSBoost/BoostRiskModel.kt`, `BoostMealModel.kt` | Parse model JSON and run live inference | Passive header/tree/schema inspectors | Compatible asset parser/load path, replay parity tests; no active model hookup |
| `openAPSBoost/HealthConnectHrIngest.kt`, `HealthConnectStepsIngest.kt` | Health history ingestion | Explicit-call Android read-only adapter | Lifecycle, grant/revoke UI, error and pagination behavior on devices, storage |
| `openAPSBoost/StepService.kt`, `StepFeed.kt`, `WearStepSource.kt` | Sensor/wear/service lifecycles | Passive step observer and diagnostics | Android service and permissions, device testing; no background initiation |
| `openAPSBoostV5/BoostHistorySync.kt` | Historical data backfill | Read-only gap and backfill diagnostics | NSClient/database backfill integration, de-duplication, recovery and permissions |
| `core/interfaces/.../OapsProfileBoost.kt` | Live algorithm profile contract | No active Boost APS profile wired | Version-compatible interface mapping, no changes to Dmitry's ISF/Autosens order |

## Safe next stages

1. Port **pure** state and format compatibility in separately reviewed commits, with
   regression tests staged independently. Retain the hidden, forcibly disabled GENERAL plugin.
2. Map source keys and serialization schemas to Dmitry's preferences **without writing**
   values; document destructive migrations before considering an adapter.
3. Compare legacy regression cases against current tests; the 71 legacy tests
   cannot be counted as migrated simply because isolated tests are green.
4. Do not register a Boost APS algorithm or change active dosing while isolation,
   missing lifecycle components and independent therapy safety validation remain unresolved.
5. After a coherent passive block, run Boost JVM tests, isolation guard, ML asset
   validation and fullDebug APK as explicitly requested by the user.

**Out of scope:** Boost V2. Preserve Dmitry's existing Autosens/ISF
correction order, and avoid duplicate sensitivity application.

**Status:** documentation/inventory update only. No tests or CI initiated for
this checkpoint. No clinical validation is claimed.
