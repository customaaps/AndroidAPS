# V1 standalone forecast-to-result integration

The existing V1 source-derived carbohydrate pre-calculation is now wired into
the IOB/COB/aCOB/ZT/UAM forecast and array post-processing in one consistent,
offline-only pipeline. Input consistency checks reject mixing parameters from
different caller-supplied meal snapshots.

A separate immutable report envelope exposes selected prediction curves but
does not implement real AndroidAPS APSResult/RT, emit a dose, or register a
pump-capable plugin. Original DetermineBasalBoost remains unported as a complete
executable APS engine. Existing Dmitry dose calculation and ISF/Autosens ordering
remain unchanged. Tests are staged; skip CI while the previous CI is running.
