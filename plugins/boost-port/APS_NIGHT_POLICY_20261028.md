# Boost APS night-mode integration — 2026-10-28

The sequential calculator applies the original `isNightModeActiveImpl` policy
to SMB permission before V1 → V5 → V6, and enforces the same permission at the
result boundary. An incoming SMB denial remains a denial. The policy does not
change ISF, targets or predictions. The APS plugin remains hidden and forcibly
disabled pending the remaining source-policy and lifecycle integrations.

## Inputs and source semantics

The APS adapter captures the original profile target before temporary-target
replacement. Enabled night mode requires this original target; missing,
nonfinite or invalid values reject the request before restoring engine state.
Disabled night mode does not read its unused offset setting.

The configured time window uses source start-inclusive/end-exclusive wrapped
boundaries; equal start/end times produce an empty window. Optional sleep
activation uses the previous cycle's sleep state. COB and low temporary-target
exceptions retain their source order. The low-target exception clamps the
active temporary target with the existing hard limits before comparing it to
the original profile target. The final glucose comparison is strictly below
original target plus the canonical mg/dL offset.

Day start is derived from the captured instant's UTC offset, matching source
wall-clock arithmetic even on DST transition days. The independent activity
snapshot now uses the same source day start. Source clock parsing retains
midnight fallback and SMART `24:00` behavior. The source one-second cache is
unnecessary at this boundary: one decision is made for each captured cycle.

A semantic `nightModeActive` flag crosses the calculator boundary. The APS
adapter translates its explanation with an English/Russian resource; canonical
algorithm traces and reference outputs remain unchanged.

## Verification

`tools/night_mode_sources.py` extracts the original decision method and clock
parser from the immutable source archive and generates a JVM-only reference.
It also copies the original NightWindow implementation into the reference
package. A 27,648-case matrix compares clock windows, all feature-flag
combinations, sleep states, COB, temporary targets and threshold glucose.

Additional tests cover strict boundaries, hard-limit clamping, independent
stored flags, invalid original targets, corrupt unused disabled settings,
real V1/V5/V6 SMB suppression, incoming denial, unchanged predictions/ISF/target,
and spring/autumn DST days. APS host tests cover original-target capture before
temporary-target replacement and localized presentation. No device or patient
validation is claimed.

Final local checks: 1177 Boost JVM tests and 7 APS integration host tests pass,
with no failures/errors/skips. The module assemble passes. Full-debug phone APK
contains the night policy, sleep owner, calculator factory and APS plugin; all
four model assets match the original archive and JVM oracle classes are absent.
APK v2 signature verifies. The plugin's hidden/disabled gate remains enforced.
