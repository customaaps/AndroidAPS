# First real pure-engine invocation in isolated KMP shadow code

This checkpoint calls the existing compiled `DetermineBasalBoostV5().decide(inputs, persisted)` using exclusively caller-supplied snapshots. Unlike earlier structural audits, it **executes the original V5 pure decision function**. It deliberately returns **no dose, insulin command, mutable state, or executable APS result**—only meal hypothesis and score for offline inspection.

Safety posture: compiled in `plugins/boost-port`, not registered in `plugins/aps`; no pump adapter, no source of live inputs, no writes to AAPS data. The wrapper rejects invalid basic numeric context, unknown clock and insulin-adding experimental preset options. More comprehensive preflight, full parity replay, missing-input handling and independent safety validation remain necessary. This guard does not make V5 clinically safe.

The V1 engine and live plugin wiring remain unported. Dmitry's existing ISF/Autosens ordering is preserved; Boost V2 excluded. Compile/JVM CI must pass before treating this as a verified integration checkpoint.
