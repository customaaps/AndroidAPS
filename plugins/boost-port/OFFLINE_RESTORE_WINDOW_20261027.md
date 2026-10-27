# Bounded offline V5 restore audit — 2026-10-27

Added a caller-fed window inspector built on the staged transition ledger. It rejects inputs longer than 1,000 frames before decoding, distinguishes empty and invalid windows, and counts genuine canonical field transitions.

This is diagnostic-only code, **not** a port of V5 persistence, APS runtime, JSON model loader or pump command path. No clinical equivalence is implied. Tests were committed but **not executed** under the user's no-test instruction. The commit uses [skip ci]. Dmitry's Autosens/ISF order remains untouched; Boost V2 excluded.
