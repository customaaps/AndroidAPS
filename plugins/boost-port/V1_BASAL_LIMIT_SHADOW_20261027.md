# Boost V1 maximum basal limit — offline extraction

Source: archived `DetermineBasalBoost.getMaxSafeBasal`. The extracted KMP diagnostic implements the three-constraint minimum using explicit, finite nonnegative snapshots. It neither emits a temp basal order nor accesses a pump, preferences, live profile or APS result. It cannot certify clinical correctness or full-engine parity.

JVM test source is staged but **not run**. Commit uses `[skip ci]` as instructed. The Dmitry insulin control path and ISF/Autosens ordering remain untouched.
