# Offline V5 restore evidence — 2026-10-27

This staged block distinguishes a missing delta-history explanation from an explicitly short non-positive delta run when reviewing apparent early session-lock disappearance. A held lock, a clock-expired lock, and an insufficiently evidenced lock release must not be conflated.

These are **diagnostic classifications**, not proof of source-engine parity: caller-supplied non-positive-history clocks are not validated against real CGM samples, and there is no live V5 state store or pump interface. No production dosing changes.

The accompanying tests are **staged, not run**. Commit deliberately uses [skip ci]. Dmitry's ISF/Autosens order is unchanged. Boost V2 excluded.
