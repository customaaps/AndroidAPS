# Source snapshot validation — 2026-10-27

The inert V6 snapshot can be independently checked using:

```sh
python3 plugins/boost-port/tools/verify_legacy_snapshot.py
```

The verifier checks every original Git blob hash against `SOURCE_MANIFEST.md`, checks uniqueness, rejects unsafe paths, and requires exactly 39 implementation Kotlin files, 71 Kotlin tests and four JSON assets.

The snapshot remains separate from Gradle source sets. Passing this check proves archival fidelity **only**: the original engine has not been ported into live Dmitry APS, and no parity, insulin, SMB or pump-control claim follows. The script has no device APIs and does not mutate the repository.

This batch is staged with `[skip ci]` while the prior full build on `bdac3afd` is pending. Boost V2 excluded; Dmitry's correction order unchanged.
