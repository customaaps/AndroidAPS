# Offline V5 stateful replay (non-dosing)

Unlike the prior per-frame caller-provided-state inspector, this bounded KMP replay invokes the existing pure V5 `DetermineBasalBoostV5.decide` repeatedly and passes the previous decision's new state into the next cycle *inside its own method*. It only exports chronological clock, score and meal-hypothesis observations. It does **not** export `V5Decision`, numerical insulin recommendations or the resulting persisted state. It does not read/write preferences, register APS or connect a pump.

The replay is not a validation of complete legacy V1 or V5 clinical parity. There are no live sensors, no device delivery semantics and no medical safety claims. The regression test is staged but not executed in this commit. Current Dmitry dosing and correction ordering are unchanged.
