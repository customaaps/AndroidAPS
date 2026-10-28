# Boost 6.2 TDD fallback integration

Source: `tim2000s/Boost-in-AAPS_3.4` at `3f75d6a36c84e048c5ddf8a5dfce935184842d11`.
Working-branch base: `85fa81592ab0dfdc6744a4e0a45e4bf81e995275`.
Source plugin blob: `85b34774c353c652dea091ec26cb7586eadf5045`, already archived in
`port-source/6.2.0/OpenAPSBoostPlugin.kt.source`. The original 114-blob archive
remains unchanged.

The transfer audit found that the active ISF preparation still used only the
older positive-TDD check. The current source adds a separate plausibility guard
before deriving ISF from TDD. This block and its pure helper are now mechanically
extracted from the pinned current plugin and included in production and the
JVM source oracle by `v1_replay_sources.py`.

Source rule: blended, adjustment-factor-applied TDD below
`0.35 * (1800 / profileSens)` does not enter the TDD-derived ISF branch.
`profileSens` is the existing activity/profile-percentage-adjusted profile ISF,
matching the upstream caller. The comparison is strictly `<`, as upstream.
The source keeps the profile ISF as the preparation's starting sensitivity and
writes the detailed `dynISF=PROFILE-FALLBACK` explanation. Existing target TT,
Autosens and glucose-velocity processing then continues in its original order.
The result still records the captured blended TDD for diagnosis.

No EMA/VWA diagnostic state is advanced by an ineligible TDD-derived preparation:
its existing `shadowEligible` flag stays false. Static-ISF operation, shared
Dynamic CR, standard Loop/pump constraints and the existing target correction
order are not replaced by the current source's separate static-Autosens rewrite.
The source information logger is omitted at the service-free boundary; the same
explanation reaches RT console output through the existing profile debug path.

The prior diagnostic CI exposed two test-only compiler errors. The comparison
fixture now declares `Map<String, Any>` explicitly, and the configuration fixture
uses the actual `PumpWithConcentration` active-pump type. Assertions and test
coverage are retained. The diagnostic APK built successfully in CI 40733233356;
that success applies to the preceding diagnostic sources, not this new fallback.

## Validation status

No tests or new APK build were run for this patch, following the requested device-
first workflow. Source extraction and the edited diff were reviewed; generation
of the production file is an implementation step, not a test result. The user
reported the preceding APK and Boost working on the phone on 29 October. This
checkpoint adds a behavior change when TDD is implausibly low and requires its
own subsequent build/device check. Earlier successful test counts do not validate
this change. No new settings or threshold controls are introduced.
