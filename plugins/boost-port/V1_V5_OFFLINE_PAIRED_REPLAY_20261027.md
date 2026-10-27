# Offline same-clock V1 diagnostic / V5 pure-engine shadow seam

The new KMP `BoostV1V5OfflinePairedReplay` executes the previously staged source-derived V1 scalar diagnostic block and the existing V5 pure shadow runner for caller-supplied, clock-aligned snapshots. It returns only diagnostic scalars, V5 state and score; **no dose or pump-command output**. Time mismatch prevents invoking the V5 engine.

This is not a full V1 determine-basal invocation and does not establish V1/V5 treatment parity or safety. No live APS input registration, device link, ISF ordering or preferences are changed. The staged test checks time mismatch; it is **not executed**, in accordance with the user's instructions. The commit includes `[skip ci]`.
