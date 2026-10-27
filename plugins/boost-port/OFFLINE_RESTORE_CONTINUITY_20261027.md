# Boost V5 offline restore continuity — 2026-10-27

This independent commonMain diagnostic inspects user-supplied replay snapshots and reports missing/corrupt state, out-of-order frames, and a suspicious disappearance of the persisted single-session lock. The lock check is intentionally **conservative and incomplete**: an early lock release can be legitimate following a sustained non-positive glucose delta; without the original delta history it must be reviewed, not treated as proven engine divergence.

The new commonTest scenarios are committed but **not executed** at the user's instruction. The entire batch uses [skip ci]. No conclusions about compilation, runtime parity or therapy safety are made.

No preferences, database, APS plugin, insulin calculations, or pump interfaces are invoked. Dmitry's correction order is unchanged. Boost V2 is excluded.
