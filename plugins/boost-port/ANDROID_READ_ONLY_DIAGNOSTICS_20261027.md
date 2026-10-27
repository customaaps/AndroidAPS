# Android read-only Boost diagnostic UI — staged

This commit adds an explicit Android Activity, an internal-only Intent factory
and a fail-closed status converter backed by the offline V1/V5 display mapper.

The Activity has `android:exported="false"`, no launcher filter and no
background sensors/APS/pump subscriptions. No UI route has yet been added to
the main AAPS navigation: application code can open this screen explicitly
through `BoostOfflineDiagnosticsLauncher.intent(context, report)`.

A valid report requires a caller-supplied historical replay. Until app data
collection is explicitly built, the screen says "No validated offline replay".
No patient data is fetched here. Do not describe this as a live dashboard.

Still missing: app navigation hook, permission-aware explicit data acquisition,
V6 source parity, full V1 `determine_basal` parity and all clinical safety
checks. No tests or CI run per explicit user instruction. The live Dmitry
ISF/Autosens ordering and doses are unchanged.
