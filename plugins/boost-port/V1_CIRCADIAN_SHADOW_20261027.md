# V1 circadian sensitivity curve — isolated offline port

The original `DetermineBasalBoost.getCircadianSensitivity` hourly polynomial is extracted into read-only `BoostV1CircadianShadow`. It exposes numeric factors only for comparison, with an explicit 0..23 clock guard. It does not change Dmitry's ISF/Autosens ordering, calculate a dose, apply an insulin-sensitivity ratio or touch APS/pump interfaces.

A JVM test file is staged but **no tests or GitHub Actions are requested**. This commit uses `[skip ci]` at the user's instruction. Functional equivalence and safety remain unverified; Boost V2 excluded.
