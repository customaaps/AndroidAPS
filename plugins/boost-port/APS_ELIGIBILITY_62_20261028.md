# Boost 6.2 eligibility transfer

Source: `tim2000s/Boost-in-AAPS_3.4:dev` at `3f75d6a36c84e048c5ddf8a5dfce935184842d11`.
Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`, base `333a1e2`.

The old Boost start/end clocks no longer participate in eligibility. Previously
the imported/default 07:00–07:01 pair could silently restrict Boost to one minute.
Those raw keys keep their identity/defaults/export registration for old backups,
but are no longer read by the calculation or displayed as editable settings.
The active screen has 65 source setting aliases; the frozen 67-entry UI archive
and 90-key defaults remain unchanged historical evidence.

Tier eligibility now follows source 6.2: the configured clock night always closes
the gate, independent of glucose/COB and the night-mode master switch. V6 also
stands down during detector-confirmed sleep and the boundary-exit hold. PRE_SLEEP
contributes through sleep-driven night mode, while it always prevents the
insulin-adding inactivity adjustment. Existing high-target and exercise handling,
per-timestamp sampler row merging and cancellation behavior are preserved.

The lie-in backstop uses this cycle's learned/configured night end, feed
availability and source StepFeed rule. Confirmed steps or HR-plus-steps wake can
end that backstop; a resume burst cannot. The genuine-wake marker is calculator
owned and resets on stop/import/restart. Clock-night protection cannot be reopened
by a false wake. Night-mode SMB suppression also reads the previous activity
cycle's lie-in flag, as in source; a fresh owner never inherits that cached flag.

Pure gate/boundary helpers are mechanically extracted from the hash-pinned 6.2
plugin already archived in the sleep block. The exact current StepFeed blob is
added and hash-pinned. A separate source namespace supplies JVM comparisons for
all mode/state/clock-boundary and failsafe combinations. Real activity/calculator
cases cover retired clocks, learned anchors, genuine wake, missing feeds,
night-toggle behavior, owner restart and incoming SMB denial. The night-mode
oracle now extracts the current source method and its night/sleep-period helper.

Verification completed: **1559 tests passed**, plus Boost KMP assembly and
fullDebug APK compilation. Standard Loop,
pump/objective/mode/SMB constraints and Dmitry Autosens/ISF correction ordering are
unchanged. Existing StepFeed future-date, negative-count and overflow guards
remain alongside source arithmetic. JVM/KMP, Android tests and full APK assembly
run on three independent matrix runners. This source eligibility change expands daytime availability compared
with the retired one-minute clock default; software tests do not establish device
or clinical performance. V2/V7 remain excluded.


## Verified checkpoint

[CI 40713999416](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40713999416)
at `0d45836217498daffd1f7f98b7843054564d6ed6`: engine job passed **1422 JVM tests**
with zero failures/errors/skips and Boost KMP assembly. XML includes 24 real
calculator boundary cases, nine activity preparation cases, seven original common
StepFeed regression cases and source eligibility comparisons. The Android job passed 137 tests (APS/Loop 62, native cadence 73, graph/catalog 2),
with zero failures/errors/skips. The separate APK job assembled and published the
fullDebug APK. All three matrix jobs passed on the same immutable source commit.
[APK artifact 12829237082](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40713999416/artifacts/12829237082).

[Source-to-target inventory](SOURCE_TARGET_INVENTORY_20261028.md) accounts for all
39 implementation units and four matching model/metadata assets, and explicitly
records the remaining diagnostic parity gaps and intended target adaptations.

The working-branch transfer preserves all production, test and workflow files from
that verified tree exactly; only this evidence, README and inventory documents
are updated. The source inventory explicitly lists remaining diagnostic parity
work; this is not a claim of a byte-for-byte copy or device/clinical validation.
