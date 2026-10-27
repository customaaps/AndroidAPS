# V1 offline cycle diagnostics aggregation

This KMP object composes the three previously ported V1 read-only scalar functions (post-rescue rebound, expected delta, circadian curve). It only returns a diagnostic report, and it fails closed if any constituent rejects its inputs.

This is **not** a full V1 calculation, V1/V5 same-cycle parity test, automated insulin decision, or clinical clearance. No active plugin, pump, Preferences, APSResult, or Dmitry ISF/Autosens integration is modified.

Kotlin test sources are staged but **not executed**. This commit has `[skip ci]` in accordance with the user's instruction.
