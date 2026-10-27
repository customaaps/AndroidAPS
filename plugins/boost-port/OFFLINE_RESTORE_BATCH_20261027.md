# V5 offline restore batch checkpoint — 2026-10-27

This batch stages a caller-fed, read-only KMP timeline inspector for multiple V5 persisted snapshots. It reuses the existing offline restore preflight gate, exposes every frame (including malformed, future-clock and absent persisted states), flags duplicate or reversed timestamps, and reports counts. The associated commonTest scenarios exercise the batch without any device or pump interfaces.

**Verification deferred:** This batch was committed with `[skip ci]` under the user's explicit request not to run tests. No compilation, unit test, GitHub Actions workflow, APK build, or clinical validation is claimed.

This is not the V5 persisted JSON/Preferences adapter, an active V1/V5 engine, or a therapeutic integration. It does not register Boost as APS and does not modify Dmitry correction order, insulin settings, or pump commands. Boost V2 is excluded.
