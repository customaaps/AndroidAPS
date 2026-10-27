# Staged V1/V5 migration safety gate

The source-derived V1 getMaxSafeBasal ceiling is now composed with the
validated V1/V5 offline replay result in a read-only migration gate.
The cap is explicitly historical diagnostic evidence, not a live insulin rate.

V1 determine_basal parity, V6 fallback parity and the AndroidAPS APSResult
adapter remain incomplete. The registered Boost plugin remains hidden,
GENERAL and forcibly disabled. Dmitry's ISF/Autosens sequencing and
real dosing remain unchanged.

No tests or CI were started, per user instruction.
