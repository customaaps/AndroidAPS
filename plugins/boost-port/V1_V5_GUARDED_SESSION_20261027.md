# Guarded V1/V5 diagnostic session

Connects V1 same-cycle provenance checks to V5's existing in-memory stateful shadow replay. Timestamp, glucose and target consistency are required; V5 is not invoked if any V1 frame fails. Reports contain only the V5 score and meal-state label, not a treatment decision, dose or live APS result. Full V1 clinical parity and pump integration are out of scope. Tests staged and not executed; [skip ci].
