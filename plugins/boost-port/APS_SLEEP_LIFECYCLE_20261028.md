# End-of-cycle sleep/history owner

Continuation on `nigthscout-dev-boost-port-20261026`, based on consolidated
remote commit `2f28ab3b857eaef0ca683e66ae1d360646160140`.
The earlier local carbohydrate diagnostics remain available. Their old base
and overlapping tests are preserved in Git stash; the former branch history
is retained as `cloud-preserved-boost-8d92fc7-20261028`.

`BoostSleepCycleOwner` ports the sleep/history orchestration from lines
2075–2195 of `legacy-source/openAPSBoost/OpenAPSBoostPlugin.kt.source`.
It calls the existing source-derived `SleepStateDetector` and
`SleepHistoryTracker`, preserving these relationships:

- Current activity and V5 use the **previous cycle's** sleep snapshot.
  After V1/V5/V6 finishes, that cycle's V1 meal-model output feeds sleep
  evaluation for the next cycle. No new independent model inference is run.
- Learned night start is clamped to a circular ±90-minute band. Learned wake
  can move at most 90 minutes later and cannot move earlier than configured.
- Learned resting HR replaces the configured fallback. The daytime HR and
  boundary-wake timestamp become available to the next activity calculation.
- Cumulative watch steps use source slot deduplication and the captured local
  day. The helper accepts an explicit phone count; the factory currently
  supplies the default zero because its phone sensor owner is not yet ported.
- Actual sleep/wake transitions open/close the 28-day history, including
  the source wake reason and sleep/preceding-day HR p10 calculation.
  Boundary wakes remain excluded from wake-time learning.

The calculator mutex owns the cache. State and changed history are written
in one `KeyValueStore.edit` under the existing import/close lock; no cached
transition is accepted after a thrown write failure. This is an atomic
preferences edit, not a claim of crash-durable disk commit. As in the source,
candidate/streak changes between transitions remain in memory, while
transition state survives recreation of the owner.

At the application boundary, malformed/unsupported stored JSON is rejected
instead of silently restoring an AWAKE/empty state. Empty first-use defaults
and legacy omitted fields remain supported. HR/step queries use one captured
timestamp and timezone offset. Unlike the source step-query fallback, a failed
query is not treated as an empty day; failures reject the calculation and
coroutine cancellation propagates.

`BoostApsCalculator.Input.cycleSpacingMinutes` forwards an explicitly supplied
cadence. The target APS caller currently leaves it null, retaining the source
detector's five-minute fallback; automatic sensor-cadence wiring is still open.
Sleep telemetry in RT, phone/Health Connect ingestion, night-mode orchestration,
post-exercise and premeal policies, settings UI and device/lifecycle validation
remain separate work. Both Boost plugins remain hidden and forcibly disabled;
V2 remains excluded.

Local JVM validation: **1,164 tests passed**, with zero failures/errors/skips.
This includes 12 new sleep-owner cases (a two-day original-source comparison,
restart, learned-window/resting-HR behavior, HR p10/history, corruption,
cancellation, write failure and import invalidation) and two new real-calculator
integration cases. The existing source transformations, 114 archived blobs,
90 settings defaults, model assets and isolation checks also passed.

`:plugins:boost-port:assemble` passed, including Android and native/common
metadata compilation. The Android AAR was inspected and contains the new
sleep owner and its calculator factory.

All **5 selected `OpenAPSBoostPluginTest` Android host tests passed**, with zero
failures/errors/skips, when run sequentially. A preliminary concurrent run
collided with the APK build's shared `core:ui` class outputs; it is superseded
by the successful sequential run. Gradle builds on this checkout must be
serialized.

The first full APK attempt was stopped after prolonged parallel DEX processing.
The sequential retry with `--max-workers=1` **passed in 3 minutes 57 seconds**,
reusing completed compilation outputs. Use one worker for full APK packaging
in this environment; no dependency version or compiler assertion was changed.

The debug APK at `app/build/outputs/apk/full/debug/app-full-debug.apk` is
240,911,549 bytes. Its v2 signature verifies. DEX inspection confirms the new
sleep owner, calculator factory and hidden APS adapter are packaged; JVM source
oracle classes are absent. Both packaged ML models match the archived bytes.
SHA-256: `c097f7c4ae2fe358f265eb2b4a67b6b7181f4b6024b4714f8597141806bddcad`.

No remote CI, release signing or device verification is inferred from these
local results. Changes remain uncommitted and have not been pushed.
