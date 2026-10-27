# Same-clock V1 diagnostic / V5 stateful shadow timeline

Combines archived-source-derived V1 read-only cycle scalar diagnostics with the existing pure V5 multi-cycle `decide` replay and its in-memory state carry-forward. Each caller-supplied frame is checked for shared timestamps and V1 input validity *before* the V5 replay. The returned timeline contains only three V1 diagnostic scalars and V5 score/meal-state text, not the insulin recommendation.

This supports testing a sequence of **synthetic or externally provided historical inputs** but does not ingest history automatically. It does not run the full V1 DetermineBasalBoost engine, so it is not an equal-algorithm clinical or dosing comparison. No APS registrations, pump commands, live therapy or Dmitry's ISF/Autosens order are changed. Boost V2 excluded.

The accompanying JVM tests are staged. Running CI verifies compilation and software behavior, never suitability for automated insulin delivery.
