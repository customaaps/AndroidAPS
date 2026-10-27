# Bulk migration verification for V1/V5/V6 — 2026-10-27

Run `python3 plugins/boost-port/tools/compare_snapshot_to_kmp.py --out /tmp/boost-port-coverage.json` after checkout.

This enumerates all 110 legacy Kotlin files (39 implementations and 71 tests) and identifies exact-name KMP candidates, byte-equal versions and missing KMP counterparts. The four critical calculation/plugin boundaries are reported separately. An exact-name match **does not imply logic parity**; implementations often require wrappers or different dependencies and cannot be declared ported merely by matching names.

Use the output to batch dependencies and actual source adaptations, not to enable a pump. The archived legacy tests are not automatically runnable. No source registration or Android manifests are modified. Existing Dmitry dosing and ISF/Autosens precedence are unchanged. Boost V2 remains excluded.

The prior successful CI covers `bdac3afd`; this tooling commit does not claim a passing build.
