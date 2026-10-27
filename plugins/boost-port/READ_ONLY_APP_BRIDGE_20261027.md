# Read-only app-facing Boost integration boundary

This patch provides a UI-neutral immutable status projection of the historical
V1/V5 offline replay. It never includes insulin quantities, actionable pump
commands or a live AndroidAPS APSResult. Apps may display these statuses only
after explicit read-only wiring and should not treat the status as clinical advice.

The final-frame V1 protection input must now equal the guard supplied to the
unified replay, to prevent accidentally mixing snapshots across cycles.

Scope: a KMP read-only adapter and tests. No active Android registration, no
V6 dosing handoff, no migration of full DetermineBasalBoost. Per user instruction,
CI and tests are not run in this patch.
