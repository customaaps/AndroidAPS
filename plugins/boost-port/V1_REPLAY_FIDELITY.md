# V1 replay fidelity checkpoint

This patch preserves the order of historical IOB arrays without assuming that
time must increase. Either strictly monotonic direction is accepted; duplicate
timestamps or a change of direction are rejected. Reordering would change an
indexed `determine_basal()` input and therefore invalidate a parity claim.

`BoostV1ReplayInputIdentity.canonical` serializes the currently captured
offline invocation fields with lossless floating-point bit representations.
The result is a **canonical payload**, not an authenticated checksum; callers
must calculate a cryptographic digest and must not claim input equivalence from
a caller-supplied opaque label alone.

The present snapshot is still incomplete relative to the source V1 function:
complete Boost profile, glucose dynamics, IOB per-point additional fields,
meal/autosens internals and optional ML model behavior remain unmapped. Do not
claim the replay is full source parity. No changes to Dmitry dosing or pump flow.
