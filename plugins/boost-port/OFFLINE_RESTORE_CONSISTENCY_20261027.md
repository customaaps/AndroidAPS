# V5 offline restore cross-check — 2026-10-27

Staged a single read-only entrypoint combining the previously isolated V5 persisted-state window and batch inspectors. It rejects invalid state, future clocks, nonmonotonic frame times and more than 1,000 snapshots before classifying the sequence. No automatic preferences, runtime I/O or insulin decisions are made.

This adds cross-check tests, not a therapeutic engine or proof of parity with the legacy device. The first successful CI build after this commit establishes only that the included software checks compile and pass. The original Dmitry APS and correction order are unchanged; Boost V2 is excluded.
