# Live aggressive-early-confirm TBR gate — 2026-10-28 verification candidate

This increment ports source commit 2221fcb96c736106cd02bc60e83f4c95c103569d
from tim2000s/Boost-in-AAPS_3.4:dev into the KMP APS boundary.

Before this change, the aggressive-early-confirm switch was passed directly
to V5ReplayContext. The APS owner now requires the source trailing-14-day
gate: at least 1000 CGM records, time below 63 strictly under 2.0%, and time
below 70 strictly under 3.5%. The same decision supplies the composed and
velocity-budget floors. The flag reaches both V5 shadow and active evaluation
through the shared context; no preference is rewritten.

All requested features share one captured-time read per cycle. With all
switches off, no history query is made. A successful history snapshot is
cached for one hour; insufficient history is retried, as in the source.
Clock reversal refreshes the cache. The target additionally rejects invalid,
nonfinite, nonpositive and out-of-window records rather than allowing them
to dilute the denominator. A read failure clears an expired allow and
returns a denied decision; cancellation propagates.

Six original source predicate scenarios were ported with only the test
annotation adapted. Ten owner scenarios cover switch short-circuiting,
captured query bounds, shared reads, insufficient history retry, exact
thresholds, deterioration, clock reversal, invalid rows, read failure,
cancellation and invalid time. Source engine/replay arithmetic and the frozen
114-file archive are untouched. The APS plugin stays hidden and disabled.

Validation is pending on the separate boost-verify branch. Only a successful
JVM/module/APK workflow permits advancing the working branch. Previous CI
40664319017 at d9acedff passed 1240 JVM tests (zero failures/errors/skips),
the module assemble and fullDebug APK build; it does not cover this new change.

Remaining work includes the newer 6.2 auto-config ownership/threshold changes,
native CGM cadence and global Loop integration, and remaining device/lifecycle
coverage. This increment does not claim full port completion.
