#!/usr/bin/env python3
"""Source-parity checks for the disconnected Boost V5 migration.

This checks archived source obligations, not correctness or live dosing.
"""
from pathlib import Path

module = Path(__file__).resolve().parents[1]
source = (module / "legacy-source/openAPSBoostV5/OpenAPSBoostV5Plugin.kt.source").read_text(encoding="utf-8")
store = (module / "legacy-source/openAPSBoostV5/V5StateStore.kt.source").read_text(encoding="utf-8")
route = (module / "src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoostV5/V5V1DelegationOffline.kt").read_text(encoding="utf-8")
for expected in ("runEngine(initiator, tempBasalFallback, v5Active = true)", "configLock", "runShadow"):
    if expected not in source:
        raise AssertionError(f"Archived V5 source contract changed: {expected}")
for expected in ("getBoostDosing(StringKey.ApsBoostV5State)", "@Volatile private var cached", "preferences.put"):
    if expected not in store:
        raise AssertionError(f"Archived V5 persistence contract changed: {expected}")
for expected in ("V1_ENGINE_UNAVAILABLE", "liveInvocationAllowed: Boolean = false", "insulinDeliveryAllowed: Boolean = false"):
    if expected not in route:
        raise AssertionError(f"Offline delegation remains incomplete/unsafe: {expected}")
print("Archived V5/V1 delegation and state obligations preserved (source-only audit).")

# A replay core does not supply the missing live V1-to-V5 plugin delegation.
v1_port = module / "src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost"
v1_sources = {p.name for p in v1_port.glob("*.kt")}
if "OpenAPSBoostPlugin.kt" in v1_sources:
    raise AssertionError("Unexpected live V1 plugin in disconnected commonMain; review isolation")
required_helpers = {"BoostV1SafetyRules.kt", "HrSourceResolver.kt", "StepSourceResolver.kt"}
if not required_helpers.issubset(v1_sources):
    raise AssertionError("Offline V1 helper coverage regressed")
if "BoostV1ReplayEngine.kt" in v1_sources:
    print("V1 replay core is present; live V1/V5 delegation remains disconnected.")
else:
    print("V1 core engine remains absent; helper-only migration cannot activate V5.")
