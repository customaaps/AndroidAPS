# Concrete APSResult interoperability (passive only)

The adapter uses the repository's real `APSResult` interface and
`RT` data class, calling `APSResult.with(RT)`. This is a genuine type-level
interoperability step, not a custom result envelope.

The emitted RT contains a timestamp and clearly marked historical diagnostic
reason only; no basal rate, duration, SMB units, carbsReq, insulinReq or predicted
dose. The APSResult implementation is provided explicitly by the caller.

**Not complete:** archived 110 KB DetermineBasalBoost contains legacy Android
and injection imports, source-only V6 dependencies, mutable state, and active
dose branches. It has not been fully adapted into compiled KMP or migrated to
current Metro DI / APSResult semantics. The passive adapter is not registered
in the active loop. Do not treat it as a dosing adapter or a verified port.

Per instruction, do not run tests or CI. Existing Dmitry dosing unchanged.
