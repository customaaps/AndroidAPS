# V5 offline restore transition ledger — 2026-10-27

Batch-staged, read-only KMP comparison of consecutive caller-fed persisted V5 snapshots. It normalizes legacy optional fields through the existing codec, identifies genuine key-level state differences and reports the first invalid frame (corrupt fields, future clocks, negative or non-increasing timestamps).

This is an offline diagnostic, not a live state store, JSON adapter, dose algorithm, APS registration or pump integration. It does not imply behavior parity for V5's active state machine or safety for therapy. Dmitry's correction order remains unchanged; V2 excluded.

Tests committed but **not executed** by user request. The commit uses `[skip ci]`; compilation and test outcomes remain unknown.
