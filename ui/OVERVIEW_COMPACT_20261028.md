# Compact Overview checkpoint

The temporary-target (ВЦ) chip now occupies 60% of its row and the temporary-basal
(ВБС) chip 40%, leaving room for an actual rate. Prediction/graph layout is unchanged.
Overview appearance settings include “Показывать ВБС в %” (default off): absolute
rate by default, percentage of current profile basal when enabled. A valid zero
stays zero; unknown/nonfinite rates display a dash. An invalid/zero profile basal
falls back to the absolute rate. This conversion only changes display.

Glucose reading age occupies a slot inside the bottom of the circle. Russian short
units are м/с; spoken descriptions retain the full age. Consumable status uses two
logical rows alongside ISF and CR, without their expanded visible labels; original
values, colors, accessibility descriptions and the expand action remain available.
Narrow screens/large fonts can use a vertical fallback for readability.

36 selected Android host tests passed, covering age placement and semantics,
compact status, narrow numeric-zero TBR, percentage conversion and related view
models. Preview rendering was inspected; no physical-device UI test was run.

Full-debug phone and Wear APKs also assembled successfully; their APK v2 signatures verify.
