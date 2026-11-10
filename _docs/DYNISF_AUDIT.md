# DynamicISF and dosing audit

Scope: `openAPSSMB` (DynISF) and `openAPSAutoISF` in the 4.0 `public_4` tree.
State: the DynISF part was fixed in commit `f04e25ed`. Everything under "Open" is still in the tree.

## Ported formula (reference)

The old (3.x / upstream 4.0) DynamicISF sensitivity, and the only formula the port may use:

```
sens(bg) = 1800 / (TDD * ln(bg / insulinDivisor + 1))
```

`DynIsfSensitivity` (`plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSSMB/DynIsfSensitivity.kt`):

- `baseFromTdd(tdd, divisor)` = the value at 100 mg/dL, **not rounded**;
- `atBg(bg, base, divisor)` = `base * ln(100/divisor + 1) / ln(bg/divisor + 1)`, algebraically the
  expression above;
- the final sensitivity is rounded to 0.1 only where the old code rounded it, never the base.

Worked example: TDD 52.421, divisor 55, BG 91 → 35.169 → 35.2.

## Fixed in `f04e25ed`

1. **Two ISF sources.** The plugin produced `variable_sens` from base-at-100 + velocity + BG cap,
   while `DetermineBasalSMB` recomputed `1800/(TDD*ln(...))` inline in five places (`future_sens`
   and the four prediction BGI sites). `velocity` moved `eventualBG` but not the dose; the
   predictions ignored `velocity` and the cap. One run could hold three different ISF values.
   Now `sens`, all `future_sens` branches and every prediction tick go through
   `DetermineBasalSMB.dynIsfSensitivity(profile, bg)`.
2. **`ApsDynIsfUseProfileSens` was dead without TDD.** `effectiveDynIsfMode` required
   `tddPartsCalculated()`, `invalidInputs` aborted on `TDD <= 0` and a fallback notification fired
   — exactly in the case the option exists for. The gate is now
   `tddPartsCalculated() || ApsDynIsfUseProfileSens`.
3. **`velocity` and `BG cap` changed the old formula.** Removed from the ISF path and from the
   settings screen. The keys (`ApsDynIsfVelocity`, `ApsDynIsfBgCap`) stay in the enum so the stored
   and synced preference schema does not change; they are unused and have no UI.
4. **COB/aCOB predictions used the current-BG ISF** while IOB/ZT/UAM used the predicted-BG ISF.
   All prediction sites now use the ISF at the BG they predict.
5. **Gating.** TDD-based DynISF again needs the full TDD history (1D/7D/24H/last4H/last8to4H) and
   falls back to plain SMB otherwise, as in 3.x. `use_TDD_for_predictions` and the inline formulas
   are gone.

Public API change: `OapsProfile.dynIsfBaseSens` replaces `use_TDD_for_predictions`.

## Open (found, not fixed)

AutoISF (`plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt`):

1. `withinISFlimits`: for resistance and step activity `finalISF = liftISFlimited * sensitivityRatio`
   instead of the upstream `max(liftISFlimited, sensitivityRatio)`. Effects multiply: lift 2.0 and
   ratio 1.3 give 2.6 instead of 2.0, i.e. ~30% more insulin for the same input.
2. Activity monitor replaces autosens: whenever `stepActivityDetected`/`stepInactivityDetected` is
   set, `sensitivityRatio = activityRatio` and the `autosensResult.ratio` branch is skipped (1.2 vs
   an autosens of 0.7 is nearly twice the insulin). `stepInactivityDetected` is
   `REST && activityRatio > 1`.
3. `ApsAutoIsfPpWeight` max was raised 0.15 → 1.0 in the working tree (not in `f04e25ed`).
   `pp_ISF = 1 + delta * weight`, limited only by `autoISF_max`; the new
   `AutoIsfPpWeightRangeTest` pins the slider range, not the effect on the dose.
4. `ActivityClassifier.phoneActivity` is never assigned, so the bicycle/walking/running rules never
   fire although the code describes the classifier as fused.
5. `StepService.getRecentStepCount5Min/10Min/15Min` return one 5-minute bucket each
   (`now-1`, `now-2`, `now-3`), not the last 5/10/15 minutes. Their sum is ~15 minutes ending before
   the current partial bucket, while `getRecentStepCount30Min` includes that bucket — mismatched
   windows and misleading names.
6. AutoISF writes preferences on every loop run (`ActivityMonitorRatio`,
   `ActivityMonitorStepsActive`, `ActivityMonitorStepsInactive`).
7. `smbRatio` can be set up to 1.0; 4.0 always used `insulinReq / 2`.

DynISF deviations that are off by default and therefore still change the ported formula when
enabled:

8. `ApsDynIsfUseProfileSens` (base from the profile ISF instead of TDD).
9. Temporary-target scaling of the ISF (`ApsAutoIsfHighTtRaisesSens` / `ApsAutoIsfLowTtLowersSens`);
   3.x had none in the ISF.
10. Activity-class multiplier on the ISF.
11. Quick-TDD fallback (`tddQuickCalculated`, last 4 h + 8–4 h); 3.x required the full parts.
12. The insulin divisor comes from `profile.iCfg.peak`; 3.x used
    `activePlugin.activeInsulin.peak`. Same value for a consistent profile, different when the
    profile's captured insulin differs from the active one.

## Verified, not a bug

- The upstream 4.0 / 4.0.0-rc1 `calculateRawDynIsf` is identical to 3.3.1.3 / 3.3.2.1 / 3.4.2.6;
  the logged sensitivities match the old formula to 0.1.
- `TddCalculatorImpl` after the KMP rewrite is mathematically equivalent to 3.4: same 5-minute
  summing, same bolus / extended bolus / TBR sources, same cache handling.
- An "illogical" `eventualBG` far above the current BG with a small positive delta is the negative
  basal IOB term: `naive_eventualBG = bg - iob * sens`, where `iob` is negative because of hours of
  zero/low temp basals. `iob` includes `basaliob`; the number a user reads as "IOB" is often the
  bolus part only. This is 3.x behaviour, not a 4.0 regression.
- An `Autosens ratio` of 2–3 in DynISF mode comes from `tddRatio / carbsRatio`, e.g. a
  `ratioFromCarbs` of 0.467 when 24 h carbs are far below the 7-day average. It multiplies basal
  and is not clamped by `autosens_max` (only the temp-target branch is). The block exists since 3.3,
  so it explains a difference from 3.1/3.2 (where the DynISF plugin forced autosens off), not a
  4.0 regression.

## Correction to an earlier assumption

`velocity = 100%` is the old 3.x curve (`sens` falls with rising BG), `velocity = 0%` is a flat ISF
at the 100 mg/dL value. The first analysis had this inverted; the test in `DynIsfSensitivityTest`
now pins it, and the port removed the parameter entirely.

## How to verify

```
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :plugins:aps:testAndroidHostTest \
  --configure-on-demand --no-daemon
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :database:persistence:testAndroidHostTest \
  --tests 'app.aaps.database.persistence.converters.APSResultExtensionTest' \
  --configure-on-demand --no-daemon
```

`DynIsfSensitivityTest` compares `atBg` against the literal old expression for BG 39–400 and divisor
55/65/75. `DetermineBasalSMBTest` checks the dosing sensitivity and `effectiveIsf` against the old
expression and replays the ZT prediction curve tick by tick with an independent implementation
(1 mg/dL tolerance from the integer rounding of the returned list).

`:app` cannot be configured while the tree has uncommitted changes: `app/build.gradle.kts` throws
"uncommitted changes" when `isMaster()` (no `-` in `Versions.appVersion`) is true. That is why the
instrumented replay (`ReplayApsResultsTest`, updated to feed `dynIsfBaseSens` from the TDD) has not
been executed.

## Open decisions

- Delete the unused `ApsDynIsfVelocity` / `ApsDynIsfBgCap` keys (preference schema, sync, simple-mode
  seeds) or keep them for schema stability.
- Strip the default-off deviations (items 8–12) for a strictly faithful 3.x port.
- Decide on the AutoISF items 1–7 separately; they change dosing and none of them are covered by a
  test that would fail if the behaviour changed.
