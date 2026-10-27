# Original V1 rebound calculation: isolated read-only adaptation

Copied the exact finite nonnegative in-domain piecewise calculation from the original `DetermineBasalBoost.postRescueReboundScale` into a KMP-only diagnostic object. The wrapper fails closed on invalid sensor readings, adds boundary tests, and exposes a scalar multiplier **for comparison only**.

The original V1 engine still depends on Android APS profile, temp basal, prediction and IOB interfaces, and is not wired. The migrated standalone function must not be interpreted as a dose recommendation or as evidence of dosing parity. The V5 shadow runner exists separately; these are not yet a same-cycle V1/V5 replay implementation.

No pump, current APS plugin selection, live IOB state or Dmitry ISF/Autosens ordering changes. Boost V2 excluded.
