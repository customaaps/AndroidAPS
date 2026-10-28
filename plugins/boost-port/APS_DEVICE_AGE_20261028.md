# Device-age telemetry — staged 2026-10-28

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
Upstream: `tim2000s/Boost-in-AAPS_3.4:dev`, pinned at
`3f75d6a36c84e048c5ddf8a5dfce935184842d11`.
This increment ports the DeviceAgeTag behavior introduced in `ec6bcb43f2` and
the first-cycle nullable-cache fix in `88850f1547`.

The APS session reads the most recent valid CANNULA_CHANGE and INSULIN_CHANGE
therapy events. The result reason includes `siteAge=<hours>; resAge=<hours>; `,
formatted to one decimal, with `-` for missing/future/invalid change timestamps.
Both timestamps are read on the first cycle and cached for ten minutes; a clock
reversal refreshes them. Session recreation after stop/import drops the cache.

The source callback is adapted to the target's suspend PersistenceLayer API.
Both read results are staged before updating the cache, so a failed second read
cannot commit half a refresh. Cancellation propagates to the existing APS owner.
Other telemetry-read failures omit this optional tag while retaining the already
calculated candidate. The portable helper uses the existing platform formatter
rather than java.util.Locale in common code.

The existing RT reason serialization carries the tag through APS storage and
Nightscout, so no database schema migration or new dosing input is introduced.
Nothing reads these tags back into the algorithm. The APS plugin stays hidden
and forcibly disabled. Dmitry's ISF correction order is unchanged.

Seven JVM test scenarios are staged: source formatting/first-read/cache cadence,
clock reversal, second-read failure, cancellation, owner recreation and invalid
timestamps. They have NOT been executed. Per user instruction, compilation,
tests and APK assembly remain deferred to the end of the port; this commit uses
`[skip ci]`. No parity, build, runtime or device-validation success is claimed.

The newer 6.2 insulin-need watch, auto-config changes, automatic CGM cadence/global
Loop wiring and remaining V5/V6 lifecycle coverage remain open in this KMP branch.
The separate `3.4.2.6-Boost_V6_261026` branch and its running build are outside
this increment.
