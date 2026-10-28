# Insulin-need watch — verification candidate 2026-10-28

The KMP APS session now evaluates the Boost 6.2 diagnostic once per day:
three complete local days against the preceding 30, at least 20 baseline
days, 25%-of-median gap exclusion, 15% profile-change exclusion and the
source two-standard-deviation band. The source is pinned to
tim2000s/Boost-in-AAPS_3.4:dev at 3f75d6a36c84e048c5ddf8a5dfce935184842d11.

TDD reads use the captured cycle time. Yesterday is anchored to local midnight,
so missing yesterday cannot shift the window backward. Profile comparison uses
the same 24 hourly stored-basal samples as ProfileSealed.EPS, dividing out a
temporary percentage while retaining a permanent change. Invalid/nonfinite
history rejects the optional diagnostic rather than producing a dose decision.

The ndw= token is persisted under a separately owned summary key and included
in the RT reason for APS storage/Nightscout. Two source cooldown keys are owned
for import/export. English/Russian INFO notifications describe dose and glucose
averages and possible confounders. A dedicated notification ID is appended to
preserve persisted notification ordinals. Fourteen-day, direction-specific
cooldown is acknowledged only after a current-generation notification has been
posted. A stale/imported/closed owner cannot acknowledge another owner's notice.
No insulin setting or dosing candidate is changed by this diagnostic.

The source numeric policy is adapted to common code; only presentation uses the
existing platform formatter. Fifteen new JVM scenarios cover statistical bands,
gaps, missing yesterday, profile normalization/change, tokens, cadence,
publication acknowledgement, restart, read/commit failure, concurrent edits
and cancellation. The earlier seven device-age scenarios are also included in
the complete module suite.

This is a verification candidate until CI finishes. Validation uses a separate
boost-verify branch; the working branch must not advance before success.
The workflow accepts boost-verify/** for this and future pre-push checks.
Requested checks: Boost JVM suite, static source/model/isolation audits, common/
native metadata and Android module/app compilation. No device/clinical validation
is implied. The APS activation gate remains in place.
