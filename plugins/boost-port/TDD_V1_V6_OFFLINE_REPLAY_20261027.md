# V1/V6 TDD setting transition offline replay

The pure archived Boost V1/V6 `boostTddSettingsTransition` rules are now called by a bounded, deterministic caller-fed replay adapter. It detects switching to V6 and enabling TDD across consecutive frames, shows the resulting intended settings per frame, and fails closed on invalid timestamps, duplicate times and nonfinite/negative input.

This is **not** a migration that writes preferences. It does not execute insulin calculations, change ISF/Autosens ordering, register an APS plugin or connect a pump. The existing Dmitry insulin algorithm remains unchanged.

A new Kotlin common test is included; running `:plugins:boost-port:jvmTest`, KMP compilation and `:app:assembleFullDebug` is required to confirm build health. This does not prove clinical safety. Boost V2 is excluded.
