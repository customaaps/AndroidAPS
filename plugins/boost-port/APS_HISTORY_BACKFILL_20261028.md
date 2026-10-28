# V5 bounded history backfill — staged 2026-10-28

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
This increment is uncompiled and untested. Per user instruction, verification
is deferred to the end of the port; this commit uses `[skip ci]`.

The V5 onboarding history reader now passes its real 14-day TDD/CGM/bolus counts
to a sequential history-sync owner. The existing `BoostHistorySync` policy
retains the 48-hour first-seen window, six-hour cooldown and three-attempt cap.
Five source counter/clock keys join the APS import/export ownership list; the
existing summary key is appended to the APS reason for Nightscout telemetry.
The source key spellings were read from the copied `3.4.2.6-Boost_V6_261026`
branch's IntNonKey/LongNonKey declarations.

State and summary are committed together. An attempt is reserved before enqueue
to avoid uncounted requests after a crash. Synchronous rejection restores the
attempt count while preserving the first-seen anchor and cooldown. Cancellation
can conservatively consume a reserved attempt. `requested` means accepted into
the queue, not downloaded; `filled` remains based on later local history counts.
History-repair failure does not prevent normal auto-configuration evaluation.

`NsClient` gains a default-declining bounded request contract. NSClient V3 queues
one request and executes it inside the existing serialized BG load, before the
normal acceptance gate. It reads date-paged glucose and treatments with local
cursors and clips ingested records to the captured 14-day window. Each collection
has a 100-page ceiling and a cursor-progress check. Completed pages are persisted;
failure after some pages is explicitly an incomplete import, not an atomic rollback.
Ordinary synchronization cursors and the global full-sync flag are untouched.
The acceptance override is passed only to bounded page processing; it is never
enabled globally for WebSocket messages. The normal sync chain can continue after
a backfill failure. No upload cursor is reset.

Pause/stop, APS owner close/import, client replacement and a request older than
six hours invalidate queued work and prevent subsequent pages. Cancellation is
propagated. Invalidation does not roll back a database page already committed.
The in-memory queue is deliberately not replayed after process death: persisted
Boost counters govern a later retry.

The APS plugin remains hidden and forcibly disabled. V2/V7 and Dmitry's
correction order are unchanged. This increment does not complete the port.

## Deferred verification

Compile common/Android/iOS metadata and run Boost plus NSClient host suites.
Add/check scenarios for accepted/declined requests, persisted restarts, deadline
and cooldown boundaries, counter corruption, write failure, cancellation/import,
overlapping sync rounds, stale/replaced clients, repeated/oversized pages, date
clipping, incremental-cursor preservation and partial imports. Verify Metro
construction of the optional runner/factory dependencies and settings ownership.
Then assemble phone/Wear APKs with the remaining port blocks. No build, test,
device or runtime-parity success is claimed for this commit.

Remaining blocks include Health Connect step-history/baseline ownership,
automatic CGM cadence/global Loop wiring and remaining activity/V5/V6 lifecycle
and telemetry integration.
