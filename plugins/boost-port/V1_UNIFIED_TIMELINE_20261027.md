# V1 unified diagnostic timeline

Adds a bounded replay of already-adapted V1 read-only input/dependency, scalar, ISF, SMB-condition and temp-basal diagnostics. Every input frame must share one positive, increasing clock across the engine contract and comparison snapshot. Failed frames do not return a partial report. The return value contains diagnostic ISF/expected-delta only, not treatment instructions.

This is not a port of the full legacy DetermineBasalBoost.determine_basal. It cannot be used for actual delivery. No tests or CI are run by this [skip ci] commit. Dmitry's ISF/Autosens order remains unchanged.
