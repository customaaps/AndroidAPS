# Final V6 override in the full replay chain

Source: `3.4.2.6-Boost_V6_091026`, commit
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. Destination:
`nigthscout-dev-boost-port-20261026`. Boost V2 is excluded.

`V6ReplayOverride` ports the final dosing block from the original
`OpenAPSBoostPlugin.runEngine`, after the V5 calculation. It preserves:

- Source branch order for selected mode, SMB permission, sleep and cumulative
  prior-volume gates; non-meal and post-rescue caps and their exemptions.
- Confirm-tranche withholding, bounded release, expiry, replacement and immediate
  invalidation when its override is skipped. Release is bounded by the remaining
  source max-IOB and confirm-shot limits and cannot be repeated from one hold.
- Bolus-primer routing and the temp-basal primer's protective/subsumed/apply
  decisions, including an already-running protective temp when V1 proposes none.
- Stock oref SMB fallback when the V5 decision is unavailable, with the original
  basal-minute rounding and bolus-step floor. It never raises a smaller V1 SMB.
- The final override's reason strings, including floor, tranche and primer-route
  diagnostics. Logging I/O, unrelated V7/acceleration/plateau shadows and additional
  Nightscout telemetry fields are excluded; full plugin telemetry is not claimed.

`BoostV1V5Replay.Frame.finalCandidate` is separate from the unmodified V1 result.
Both remain internal replay DTOs and do not implement `RT`/`APSResult` or issue
commands. A V5 calculation exception produces an unavailable decision and the
source fallback; `nextV5State` retains the prior state on that failure. The V5
failure is explicit in the frame. Recorded source `activeMode` simulates selection
and does not activate this port.

Tranche state belongs to one sequential replay instance. It is not serialized,
matching the source controller: a process restart drops an outstanding hold.
V1's feature history and V5's persistent meal/session state retain their existing
separate restore boundaries.

## Source and reference

`tools/v1_replay_sources.py` checks the complete portable override against a
reviewed extraction of the source block and its helpers. The only dosing-math
platform substitution is Java `Math.round` to KMP `roundToLong`. Recorded inputs,
settings and the separate result replace application services. Its ordinary
mode is read-only; `--write-port` remains an explicit maintainer operation.

The JVM oracle independently compiles the extracted source block with its
original `ConfirmTrancheController` and original `Round` implementation
(Java `BigDecimal`). The latter is archived verbatim under `port-source` for
reproducibility without Git objects or network access. These source blobs are
checked before generation:

| Source | Git blob |
| --- | --- |
| OpenAPSBoostPlugin | `e33fba201719c3400b68b9c27a3453a2d85537ab` |
| ConfirmTrancheController | `1baa8290ce84cac4e749fd0c1665d4f88ed21d9e` |
| core/interfaces/utils/Round | `dbf9863fd39c61a636e2c446eb9304828c209d79` |

The original 114-file legacy snapshot is untouched. Generated reference classes
are restricted to `jvmTest`; the application uses the portable override.

The 16 new tests exercise all meal states and permission combinations, threshold
boundaries, null/unavailable results, exemption caps, protective/subsuming temps,
tranche cadence/headroom/reset/restart, source rounding and V5 calculation failure.
They compare every result field, final reason and held amount with the reference,
as well as explicit expected outputs. The existing 36-cycle real-model V1/V5
comparison now also checks the final V6 candidate and source override state.
Synthetic decision controls in seam tests are distinct from complete engine
calculations; neither is recorded-patient replay.

The override checkpoint passed **1005 Boost JVM tests**, with zero failures,
errors or skips. `:app:assembleFullDebug` succeeded in the same invocation.
The later resource-only Russian-settings update is separately built and checked
in the final APK; no new algorithm test count is inferred from that resource build.

## Preserved source limitations

The cumulative rule still checks **past** SMB volume, without reducing a current
SMB to remaining rolling-window headroom. This behavior is characterized again
at the override, not promoted to a hard rolling-window bound.

The temp-basal primer calculates an incremental rate for 30 minutes but retains
`max(V1 duration, 30)`. A 0.3U primer over an existing 40-minute plan can therefore
produce more than 0.3U of incremental basal before downstream application
constraints. This duration behavior is preserved and explicitly tested; the
source's dose-size language is not a strict amount guarantee in that case.

The final source condition uses the selected mode, SMB permission, sleep and
cumulative checks. It does not separately check `profile.boostActive`; source
equivalence here does not establish enforcement of the user's full time-window
configuration. No new predicate has been silently added during extraction.

## Remaining application work

Full live input/profile construction, application settings/import lifecycle,
plugin integration and downstream pump constraints remain disconnected. The
registered inspection plugin is still hidden, GENERAL and forcibly disabled.
Independent recorded-cycle evidence and device/ART verification are outstanding.
The four previously requested switches remain in place. Git publication is
handled separately; this checkpoint does not imply remote-CI success.

## Russian settings

The KMP shared-preference resources previously had only 53 of 338 Russian
strings. All 285 missing labels, descriptions and list entries are now supplied;
the generated report confirms `values-ru-rRU 338/338 complete`. Existing unclear
APS/ISF/basal-limit and OTP/PIN descriptions were corrected. The SMS setup and
permission text and AutoISF description/Autotune peak label were also reviewed.
Abbreviations such as SMB, IOB, COB, APS, ISF, UAM, BG, CGM, OTP and PIN retain
their original spelling, as requested. Protocol commands, setting keys, numeric
defaults and algorithm behavior are unaffected by the localization changes.

XML parsing, duplicate/name coverage, format placeholders and abbreviation
retention were checked. The localization changes do not constitute a device
layout test or a translation of unrelated screens throughout the application.

## Persistent replay lifecycle

`BoostReplaySession` now executes the complete V1/V5/V6 chain with automatic
restore on its first call and save after every calculated cycle. A single
`BoostReplayStateCodec` envelope contains the source-shaped V1 feature ring and
all 15 legacy V5 persistent fields. It deliberately omits the three source
cache-only score/null-streak clocks and the in-memory confirm-tranche hold.
`BoostReplayStateStore` retains the full current object in its process cache,
including transient fields, before calling the backing store, so rapid invokes
cannot reload stale state and lose the meal-session lock.

The explicit `KeyValueStore` boundary is available on Android and common KMP.
The separate `boost_port_replay_session_v1` string key is registered with the
disabled inspection plugin for key resolution/import/export. No source live-APS
key, Simple Mode mask, preference UI or automatic scheduler is used. One owner
must use a store/session sequentially; no concurrent execution is claimed.
Create a new store and runner after external state replacement/import. App-wide
import/stop/start hooks and live-plugin ownership are still not wired.

Only an actual empty default starts a fresh replay. Corruption, unknown envelope
version, wrong JSON value types, excessive size/nesting or storage read failure
rejects restoration without clearing or overwriting the old value. This is
deliberately stricter than the source store's corrupt-JSON reset to IDLE; parity
is claimed for valid source payloads, not that corruption branch. Explicit legacy
blob import retains source-compatible missing-field defaults.

A write failure is returned separately from the calculated frame and leaves the
latest in-process cache intact. A successful `putString` means the backing API
accepted the write; disk flush/crash durability is not inferred from an async
Android preference API. The envelope uses one string write so its V1/V5 fields
are not split across independently visible updates.

The JVM reference now includes the original `V5StateStore` parsing, cache and
serialization, adapting only its preference/log interfaces. Its source blob is
`26fa560c847a6bc37d4055deb9236f1c8bc6cfdb`. Seven new tests compare typed JSON
fields and cold-start restore with that code, cover delayed visibility and I/O
failures, reject malformed snapshots without calculation, and run 20 complete
real-model cycles across a real temporary-file restart while preserving the meal
lock and V1 feature history.

## Latest local verification

With the persistent session included, all **1012 Boost JVM tests** pass with
zero failures/errors/skips. `:app:assembleFullDebug` passed in the same run.
The APK signature, runtime replay/session classes and isolated state/switch keys
were verified; generated JVM reference code is absent. Both model assets match
their verified source bytes. The final resource table contains Russian values
for all 338 shared setting strings, and all 285 additions plus the source/OTP
switch summaries match their packaged values exactly.

Final APK: `app/build/outputs/apk/full/debug/app-full-debug.apk`, 259,311,017 bytes.
SHA-256: `1e1e7334aaf736a394a534afaab7610293ade4b75034615d937fedb7721f821c`.
Earlier checkpoint artifacts are superseded. The changes target the requested
destination branch. No remote-CI or device execution is claimed by this evidence.
