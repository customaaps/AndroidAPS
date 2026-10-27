# Boost V1 replay parity checkpoint — 2026-10-27

The original V1 engine is preserved in `port-source/DetermineBasalBoost.kt.source`, not compiled into the APS runtime. Offline `BoostV1ReplayParity` compares independently captured reference/candidate outputs.

The four scenario groups HYPOGLYCEMIA, MAX_IOB, SMB and UNRELIABLE_CGM, plus NORMAL, are **required coverage labels**. Unit tests currently use synthetic outputs to test the comparison mechanism. They **do not** compare original and ported `determine_basal` calculations.

This commit intentionally triggers the existing `boost-port-ci.yml` push workflow on the dedicated branch. It runs KMP JVM tests, Boost isolation checks, and the fullDebug APK build. The Android Boost APS runtime remains disabled. Build success does not imply dose correctness or clinical safety.

**Outstanding:** compile a separate non-actuating source-faithful V1 engine with its dependencies; capture reference and candidate outputs using identical stored glucose/IOB/profile/time/carb histories; produce independently verified input provenance and per-scenario replay results. No real pump commands may originate from this stage.
