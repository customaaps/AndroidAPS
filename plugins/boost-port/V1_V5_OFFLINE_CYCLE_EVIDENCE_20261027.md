# V1/V5 side-by-side offline evidence envelope

This incremental KMP component gathers caller-supplied V1 scalar diagnostic inputs and precomputed V5 shadow score/state evidence into a single chronological batch. It fails closed on missing V5 evidence or invalid V1 diagnostics.

**Limitations:** the V1 scalar diagnostics are not a full V1 engine; this object does not execute V5, compare treatment doses, run historical replay itself, interface with AAPS or validate medical safety. A true same-cycle V1/V5 parity engine and independent clinical review are still required before any live insulin control.

Test source staged only; **no tests or GitHub Actions executed**. Commit uses `[skip ci]`. Dmitry's insulin path and ISF/Autosens order untouched; V2 excluded.
