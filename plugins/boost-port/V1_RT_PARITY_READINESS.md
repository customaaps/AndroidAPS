# Required before full V1 RT parity

The port currently produces an **offline diagnostic envelope**, not a
source-equivalent V1 RT. The new `BoostV1RtComparisonReadiness` records
missing inputs explicitly and always blocks strict RT parity until executable
source-equivalent treatment decision fields are implemented and validated.

Missing components: full legacy profile/IOB/meal adaptation, source decision
ordering and all max-IOB/SMB/hypoglycemia protection branches, independent
source/candidate calculation on the same recorded snapshots, strict dose and
temp-basal output comparison, and a final integrated build/test run.

Do not infer clinical readiness from successful compilation or diagnostic
output, and do not connect offline outputs to pump dispatch.
