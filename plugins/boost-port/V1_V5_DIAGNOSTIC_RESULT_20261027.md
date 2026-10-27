# Unified V1/V5 diagnostic output

Adds an immutable non-dosing view of the validated V1/V5 stateful shadow session with chronological and numeric checks. This is **not** a native AndroidAPS APSResult/RT and does not connect either Boost engine to active APS or a pump. No insulin recommendations are exposed. Unit tests are staged. The existing GitHub Actions workflow will run KMP tests and attempt the fullDebug APK on this commit.
