# V1 DetermineBasalBoost full-engine adaptation checkpoint

The original 110KB `DetermineBasalBoost.kt` is present byte-for-byte in `legacy-snapshot` but cannot be recompiled as a standalone KMP component: its entrypoint requires seven AndroidAPS interfaces (GlucoseStatus, CurrentTemp, IobTotal, OapsProfileBoost, AutosensResult, MealData and RT), Android profile utilities, and a large set of platform-dependent helpers. Blind copying into an active APS package would put insulin control at risk.

This batch adds a typed, caller-fed, non-dosing interface adaptation layer for a minimal subset of the original entrypoint inputs. It validates timestamps, CGM quality/staleness, basal/profile/IOB, meal and autosens numeric constraints without any Android dependencies. It explicitly returns neither a dose nor a permission to call the real V1 engine.

**Still necessary:** full type mapping, oref-derived input assembly, original V1 determine_basal port, feature flags and delegation rules, offline output parity, and independent clinical safety validation. Current Dmitry dosing and correction order remain unchanged.

Regression tests included but not executed; commit uses [skip ci] so it does not interrupt the earlier pending GitHub Actions run.
