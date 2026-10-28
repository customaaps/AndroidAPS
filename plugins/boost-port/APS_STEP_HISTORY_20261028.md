# Step-history activity owner — staged 2026-10-28

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
This is an uncompiled, untested increment. Verification is deferred to the end
of the port per the user's instruction; commits use `[skip ci]`.

The Android activity lease now owns hourly reads of 28 days of Health Connect
step intervals, separately from the HR worker. It carries PHONE device metadata
into the common snapshot builder so HC can backfill the phone baseline. Existing
read permission checks, bounded pagination and failure-vs-empty distinctions
are retained. No partial read is published. The cache is immutable, expires after
two hours, and is invalidated on disable/close or an unavailable read. A generation
counter prevents an older read republishing after invalidation. The current offset
must match the captured snapshot; no previous-day HC total seeds the new day.

The common APS owner merges every HC origin and phone-device history with
completed Wear days. Wear history is fetched hourly; current-day SC rows are
read each cycle. Source helpers retain hold-higher reconciliation, per-source
daily maxima, banked day-close, phone-anchored calibration, source selection and
baseline/intraday shadow arithmetic. The two source preference keys are committed
together after cancellation/concurrent-setting checks. Failed optional shadow
preparation does not replace the already calculated insulin candidate.

The HC current-day dominant-source total can seed the phone daily ledger upward,
as in the source, while sensor feed availability remains separate. Neither an
empty HC response nor a permission failure creates measured inactivity. Completed
history remains persisted across lease recreation. Cross-midnight intervals keep
the source's start-day attribution rather than a new allocation algorithm.

Twelve optional source activity fields were added to RT. The existing RT JSON
path carries them into APS result storage and Nightscout; old results have null
defaults. The source diagnostic percentages do not change ISF, targets, basal or
SMB output. The APS plugin remains hidden and forcibly disabled. V2/V7 remain
excluded; Dmitry's sensitivity correction order is preserved.

## Deferred checks and remaining integration

At the final verification stage, compile the common/Android/native targets and
exercise HC pagination/metadata, lease disable/re-enable/close, cancellation,
offset/day changes, no-feed cases, seven-day baseline cold starts, source swaps,
hold-higher day-close, atomic preference failures and APS/SQLite/Nightscout JSON
round trips. Earlier passing checkpoints do not cover this increment.

Automatic native CGM cadence/global Loop wiring, newer upstream 6.2 features in
the KMP port, remaining V5/V6 lifecycle telemetry and device verification are
still open. The separate `3.4.2.6-Boost_V6_261026` branch is synchronized through
upstream `3f75d6a3` as recorded in its own inventory; it has no newer upstream
commits at this checkpoint.
