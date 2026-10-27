# Boost V1/V5/V6 original source snapshot — 2026-10-27

## Verified archival transfer
The preceding commit `4730e51d` used GitHub's existing verified source-branch Git blobs to stage the complete set of **114 original files**, all SHA-equal to `SOURCE_MANIFEST.md`: 39 Kotlin sources, 71 Kotlin legacy tests and four JSON ML assets.

Files are under `plugins/boost-port/legacy-snapshot/<original-relative-path>`. Every archived Kotlin file has an additional `.source` suffix and therefore is **not compiled or registered by Gradle**. The JSON assets are kept under the inert snapshot tree, not promoted to active app assets. The archive is original reference data, not an adapted production implementation.

## Integration still outstanding
- Implement and review V1 DetermineBasalBoost and OpenAPSBoostPlugin against Dmitry's actual current interfaces.
- Implement and review V5 DetermineBasalBoostV5, its plugin orchestration, state/persistence and configuration boundaries.
- Review each V6 feature delta, test engine/output parity using synthetic and historical offline inputs.
- Inventory source regression tests and convert applicable cases; archiving a Kotlin test does not execute it.
- Validate stale CGM, hypo, IOB/SMB bounds, device failures and pump constraints independently.
- Preserve Dmitry's original ISF/Autosens correction ordering and avoid any duplicate ratio adjustment.

No APS registration, pump commands, insulin delivery, preference mutation, or live engine activation is introduced by this checkpoint. Boost V2 is outside scope. **Passing CI would demonstrate compilation and existing isolated checks only, not clinical safety.**

This documentation commit intentionally requests the current GitHub Actions suite for the new target HEAD.
