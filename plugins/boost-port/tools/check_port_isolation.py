#!/usr/bin/env python3
"""Check pure-engine isolation and explicit selection of the live Boost APS adapter.

The older inspection plugin remains hidden/disabled; Loop owns all pump commands.
This narrow static guard complements runtime activation and constraint tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "plugins/boost-port"
PLUGIN = MODULE / "src/commonMain/kotlin/app/aaps/plugins/boostport/BoostPortInspectionPlugin.kt"
KEY = MODULE / "src/commonMain/kotlin/app/aaps/plugins/boostport/BoostPortKey.kt"
V5_ENGINE = MODULE / "src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoostV5/DetermineBasalBoostV5.kt"


def require(text, token, where):
    if token not in text:
        raise AssertionError(f"{where}: required safety marker absent: {token}")


def main():
    plugin = PLUGIN.read_text(encoding="utf-8")
    key = KEY.read_text(encoding="utf-8")
    require(plugin, ".mainType(PluginType.GENERAL)", PLUGIN)
    require(plugin, ".enforce(EnforcedState.Disabled)", PLUGIN)
    require(plugin, ".showInList { false }", PLUGIN)
    require(key, 'key = "boost_port_audit_requested"', KEY)
    require(key, "defaultValue = false", KEY)

    # The old inspection plugin stays hidden, GENERAL and disabled. The live APS
    # adapter is registered separately in plugins:aps; the pure engine owns no pump APIs.
    for forbidden in ("PluginType.APS", "EnforcedState.Enabled"):
        if forbidden in plugin:
            raise AssertionError(f"{PLUGIN}: forbidden activation marker: {forbidden}")
    gradle = (MODULE / "build.gradle.kts").read_text(encoding="utf-8")
    for forbidden_dependency in ('project(":plugins:aps")', 'project(":pump:'):
        if forbidden_dependency in gradle:
            raise AssertionError(
                f"{MODULE}/build.gradle.kts: direct APS/pump dependency is forbidden: "
                f"{forbidden_dependency}"
            )
    if V5_ENGINE.exists():
        engine = V5_ENGINE.read_text(encoding="utf-8")
        require(engine, "class DetermineBasalBoostV5 {", V5_ENGINE)
        require(engine, "fun decide(inputs: V5Inputs, persisted: V5PersistedState): V5Decision", V5_ENGINE)
        for forbidden in ("@Inject", "@Singleton", "import javax.inject", "PluginType.APS"):
            if forbidden in engine:
                raise AssertionError(f"{V5_ENGINE}: forbidden active-engine marker: {forbidden}")
    else:
        raise AssertionError(f"{V5_ENGINE}: original V5 orchestrator missing")

    replay = MODULE / "src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1ReplayEngine.kt"
    if replay.exists():
        core = replay.read_text(encoding="utf-8")
        require(core, "internal class BoostV1ReplayEngine(", replay)
        for forbidden in (
            "@Inject", "@Singleton", "PluginType.APS", "import java.",
            "import app.aaps.core.interfaces.aps.APSResult",
            "import app.aaps.core.interfaces.aps.RT",
            "import app.aaps.core.interfaces.queue.",
        ):
            if forbidden in core:
                raise AssertionError(f"{replay}: forbidden runtime/platform binding: {forbidden}")
        result = replay.parent / "replay/V1ReplayResult.kt"
        require(result.read_text(encoding="utf-8"), "internal data class V1ReplayResult(", result)

        # Replay model/input/coordinator additions must not acquire live bindings.
        replay_files = list((replay.parent / "replay").glob("*.kt"))
        replay_files += list((MODULE / "src/androidMain/kotlin/app/aaps/plugins/aps/openAPSBoost/replay").glob("*.kt"))
        for path in replay_files:
            content = path.read_text(encoding="utf-8")
            for forbidden in (
                "@Inject", "@Singleton", "PluginType.APS", "SharedPreferences",
                "import app.aaps.core.interfaces.aps.APSResult",
                "import app.aaps.core.interfaces.aps.RT", "import app.aaps.core.interfaces.queue.",
            ):
                if forbidden in content:
                    raise AssertionError(f"{path}: forbidden live replay binding: {forbidden}")

    # JVM compilation of a common source does not by itself prove KMP portability.
    # These source APIs caused the 47e698c common-metadata CI failure.
    activity = MODULE / "src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/activity"
    for path in activity.glob("*.kt"):
        content = path.read_text(encoding="utf-8")
        for forbidden in ("String.format(", ".removeIf {", "import java."):
            if forbidden in content:
                raise AssertionError(f"{path}: JVM-only common API: {forbidden}")

    # The APS adapter is user-selectable. It may neither force itself on nor replace
    # the user's default algorithm. Pump commands remain owned by the standard Loop.
    aps = ROOT / "plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/OpenAPSBoostPlugin.kt"
    if aps.exists():
        content = aps.read_text(encoding="utf-8")
        for marker in (".mainType(PluginType.APS)", ".showInList { config.APS || config.AAPSCLIENT }"):
            require(content, marker, aps)
        for forbidden in (".enforce(", ".setDefault(", "import app.aaps.core.interfaces.queue.", "deliverTreatment(", "commandQueue."):
            if forbidden in content:
                raise AssertionError(f"{aps}: delivery must remain owned by Loop: {forbidden}")

    print("Boost KMP isolation guard passed (static checks only).")


if __name__ == "__main__":
    main()
