# Native CGM cadence and global Loop bulk transfer

Source: `tim2000s/Boost-in-AAPS_3.4:dev` at
`3f75d6a36c84e048c5ddf8a5dfce935184842d11`.

The autosens data store now detects the median raw-reading spacing, ignoring
duplicates, intervals of six seconds or less, and gaps over an hour. A feed below
two minutes gets a separate regular native grid; other feeds share the existing
five-minute buckets. Interpolation handles irregular backfills. The five-minute
grid, reference anchor, IOB/COB caches and Dmitry's Autosens/ISF correction order
remain intact. All new store properties use the existing common lock, survive
cloning, and expose copies through the interface. Native freshness uses the
target's existing outdated-BG limit.

The shared SMB glucose-status reader uses that native series with the original
elapsed-time windows and mg/dL per five-minute delta units. A missing native
series falls back to the existing buckets. The APS adapter supplies the measured
spacing to Boost's sleep telemetry. The V5 sustained-score/age implementation was
compared with 6.2 and already uses elapsed time; source cadence regression tests
are added without changing its calculation.

`loop_at_native_cadence` is a global preference, off by default, exposed in Boost's
common settings with English/Russian text. When enabled the automatic Loop trigger
reads native BG and accepts each newer reading. Otherwise it reads the existing
buckets and requires 270 seconds of glucose-time advance, preserving the source
30-second tolerance for five-minute sensors. Duplicate and older values never
trigger. The KMP PostCalculationRunner retains pending-BG recovery after a replaced
chain and cancellation rollback only when no decision was stored. The existing
Loop continues to own pump enactment.

Four exact upstream files are separately preserved under `port-source/6.2.0`:
AutosensDataStore, AutosensDataStoreObject, InvokeLoopWorker and
GlucoseStatusCalculatorSMB. The original 114-file archive is unchanged.

Five local pure scheduling tests passed. CI also runs the existing autosens store
suite with added native/backfill/clone/freshness cases, the post-calculation suite
with cancellation and native/default selection cases, SMB glucose-status tests,
Boost JVM tests, module assembly and fullDebug APK compilation. The completed CI results are recorded below; device/clinical validation is separate.

Boost remains hidden and forcibly disabled. V2/V7 remain excluded. Further work is
the final source inventory and Android lifecycle/import/export/device scenarios.

## Verified checkpoint

[CI 40675993285](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40675993285) passed the 46-case autosens store
suite, including all nine added native-grid/clone/freshness cases, plus 1399 Boost
JVM tests and module assembly. [CI 40679752693](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40679752693) passed
all 13 post-calculation, five Loop cadence and nine SMB glucose-status tests,
followed by fullDebug APK compilation. XML reports show no failures/errors/skips
and explicitly contain both cancellation cases and native/default selection.
Production sources were identical across the checkpoints; the guarded validation
rerun changed only two test fixtures and its workflow. The working branch restores
the complete CI pipeline for all subsequent changes.
