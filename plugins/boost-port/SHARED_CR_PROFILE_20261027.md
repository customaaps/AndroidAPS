# Target profile preparation and shared Dynamic CR

Source: `3.4.2.6-Boost_V6_091026`, commit
`bc02e4c49f2b9bb5d50a5938b9a09884c76140cd`. Destination remains
`nigthscout-dev-boost-port-20261026`. Boost V2 is excluded.

The preceding V1/V5/V6 replay and persistence stage was published in preliminary
commit `4973a4bce363d88e688b2d6acd8f3030f8e2cddf`.

## ISF and profile preparation

`BoostIsfPreparation` ports the complete numeric source preparation before
`determine_basal`: five TDD components, recent-eight-hour pull-down, adjustment
factor, target/percentage scaling, temporary-target adjustment, alternative
oref Autosens path, BG cap and variable sensitivity. Settings and TDD readings
are explicit inputs. Logging, EMA/VWA diagnostic shadows and their extra history
reads are excluded without changing the dosing arithmetic.

`BoostTargetTddReader` uses the target KMP `TddCalculator` API with one explicit
timestamp for the two daily averages and three rolling windows.
`BoostTargetProfileAdapter` builds all 73 Boost profile fields from the target
`OapsProfile`, prepared ISF and captured Boost activity/recovery/meal context.
It preserves already constrained basal/IOB/bolus values and canonical insulin
units; it does not perform another concentration conversion or mutate the
incoming Autosens object. The source's sensitivity-ratio selection is retained.

The JVM oracle extracts the source ISF body and profile constructor. Tests cover
2,000 ISF combinations, all profile fields across 72 context combinations,
single Autosens application, recovery/peak boundaries, target TDD queries and
full V1 calculation with prepared profiles. Source and target formatting are
aligned in the full-cycle comparison, including reason strings.

## Shared setting for all APS algorithms

Dynamic CR is in **General → Dynamic CR**, independent of the Boost plugin.
The master switch defaults off. Controls retain source keys/defaults/ranges:

| Setting | Default / range |
| --- | --- |
| `use_dynamic_carb_ratio` | off |
| `dynamic_cr_formula` | logarithmic; sigmoid also available |
| `dynamic_cr_adjustment_factor` | 0.5 / 0–2 |
| `dynamic_cr_weight_percentage` | 0.65 / 0–1 |
| `dynamic_cr_use_custom_peak_time` | off |
| `dynamic_cr_peak_time` | 75 min / 35–110 min |

The pure `DynamicCarbRatioCalculator` now lives in `core:interfaces` so both
the real APS modules and the separate Boost module use it without an APS-module
dependency. The verbatim source archive has Git blob
`d52220b5e148ccd276915cde90d4b473e332324e`; the source tool verifies the portable
namespace/DI-only transformation and generates a JVM oracle with original
BigDecimal-based rounding. Two thousand formula/TDD-fallback comparisons cover
both formulas and diagnostic strings. Autosens-range and half-to-double result
clamps, peak limits and source fallback rules are preserved.

`DynamicCarbRatioPolicyImpl` is the application's shared DI binding. SMB, AutoISF
and AMA now require that policy and use its returned CR in their algorithm input
profile. When disabled it returns the original CR before any extra TDD query.
When enabled it reads the configured formula and TDD, reports its calculation
reason, and falls back to profile CR on unavailable/invalid data. Coroutine
cancellation is propagated. SMB reuses its complete raw TDD components; Boost
can pass its captured TDD rather than reading the same history again.

The Boost adapter's `prepareWithSharedCr` uses the identical policy. It requires
the **original profile CR** explicitly because a reported target OapsProfile may
already contain a dynamic value. The baseline remains in `carb_ratio` and the
calculated result is placed in the source `dynamicCarbRatio` field, so V1 applies
it once. Original stored profiles and manual bolus-wizard profiles are not edited.
The user request covers APS algorithms, not a global rewrite of every profile.

The shared controls have English/Russian resources. CR, TDD, APS and other
abbreviations retain their spelling. Shared Russian preference coverage is now
349/349, extending the earlier 338-string checkpoint.

## Build identity

The source branch declares `Boost_Vladimir`. Android's About dialog now uses
the separate `4.0.0_Boost_Vladimir` display label, with no `dev` in that name.
The technical version and Git revision remain separate for version comparison,
development gates and traceability; this display change is not a release-status
change.

## Validation checkpoint

On 2026-10-27, the complete Boost JVM suite passed **1,022 tests**, with no
failures, errors or skips. The new shared-policy host suite passed **7 tests**,
the three APS plugin suites passed **9 tests**, and the selected safety/version
suites passed **64 tests**. The final Boost suite was rerun after requiring the
original profile CR explicitly in the adapter. `:app:assembleFullDebug` passed.

The APK signature was verified. Its DEX contains the shared CR calculator/policy,
ISF/profile adapters and V1/V5/V6 replay/session class definitions; JVM oracle
definitions are absent. All 349 Russian shared-setting entries are present,
including exact matches for the 11 new Dynamic CR translations. Model asset
bytes match the verified source copies. Generated BuildConfig and DEX contain
the `4.0.0_Boost_Vladimir` display label. The 114-blob legacy snapshot, source
transformations, asset validation, isolation guard and whitespace check pass.

The main-screen ISF/CR display is a **proposal only** in this checkpoint. The
user asked to see its layout first: two lines in the existing sensitivity block
below IOB/COB, for example `ISF 5.6 → 4.6` and `CR 10.0 → 8.0`. Left is the
profile baseline and right is the value actually used for that calculation.
No main-screen value-display changes are included yet.

## Still outstanding for Boost

The numerical preparation and common policy do not complete live Boost
integration. Activity/sensor/settings collection and lifecycle, source-mode
configuration, APS registration/invocation and downstream delivery integration
still need work. Recorded-cycle and device/ART validation remain outstanding.
The inspection plugin stays hidden, GENERAL and forcibly disabled. The shared
CR feature is wired into the three existing APS algorithms; Boost consumes it
through its prepared-profile boundary until its live integration is completed.
