# Boost V1/V5/V6 delivery closeout — 29 October 2026

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
Reviewed base: `b814ad546df6f9e71525436d4b2e74651ee6bee1`.
Source: `tim2000s/Boost-in-AAPS_3.4:dev` at
`3f75d6a36c84e048c5ddf8a5dfce935184842d11`, unchanged on this review.

## Scope and remaining work

The agreed V1/V5/V6 implementation inventory accounts for 39 units and four model/
metadata assets. The active calculation, policies, source 6.2 additions and
recorded diagnostics are connected. Review found no remaining unconnected stage
between selected Boost and the selected pump. That is a code-integration result,
not a claim that every behavior has been executed or that no bugs remain.

Dmitry's ISF/Autosens order, shared Dynamic CR, target defensive checks and platform
UI remain intentional target adaptations. V2/V7 remain outside the agreed scope.
The hidden General inspection plugin is separate from the selected live APS at
registration key 236. Internal `Replay` type names identify reusable source-shaped
calculation/state components; the live calculator executes those same components.
Their misleading older offline-only comments are corrected with the generators.

## Reviewed enactment route

| Stage | Existing implementation and behavior |
|---|---|
| APS selection | `ApsPluginRegistrations.openApsBoostEntry`, key 236, selects `OpenAPSBoostPlugin` as APS. |
| Calculation | `BoostApsCalculatorFactoryImpl.Calculator.calculate` assembles the captured inputs and runs V1 → V5 → final V6 override. |
| RT output | `BoostApsResultMapper.toRt` copies final `units`, `rate`, `duration`, `deliverAt` and timestamp, with `Algorithm.BOOST`. |
| Standard result | `OpenAPSBoostPlugin.invoke` publishes `apsResultProvider().with(calculated.result)` as `lastAPSResult` in its current generation. |
| Request flags | `DetermineBasalResult.with` maps RT units to `smb` and rate/duration to a TBR request. `APSResult.isBolusRequested` is true when SMB is positive. |
| Loop | `LoopPlugin.invoke` invokes the selected APS, stores its result, clones it and applies basal, percent-basal and bolus constraints. No algorithm-specific exclusion drops BOOST. |
| Enactment | Permitted closed-loop requests go to `applyTBRRequest` first; after a successful/no-change TBR result, positive SMB goes to `applySMBRequest`. |
| Queue | TBR uses `tempBasalAbsolute`/`tempBasalPercent`; SMB uses `DetailedBolusInfo` with `BS.Type.SMB` and the request deadline, then `commandQueue.bolus`. |
| Pump | `CommandSMBBolus.execute` calls the selected pump's `deliverTreatment`; TBR commands use the selected pump's basal interface. The concentration wrapper remains the canonical unit boundary. |
| Virtual pump | `VirtualPumpPlugin` implements these same methods and records enacted bolus/TBR history through `PumpSync`. No separate Boost pump driver is required. |

Mode, objectives, pump readiness, SMB enable/interval, request expiry, import hold
and the standard constraint chain remain in that route. The user chooses the APS,
Loop mode and pump through the normal configuration.

## Final correction: percentage-basal result cloning

Loop computes `usePercent` and `percent` before `newAndClone()`. The clone was
rebuilt only from RT, which contains neither caller-computed field, so the new
object reverted to `usePercent=false` and `percent=0`. This could discard the
virtual pump's selected percentage representation before the constraint/enactment
branch. The clone now copies those two fields after rebuilding the RT result.
For example, an existing `usePercent=true, percent=120` remains that representation
in the constrained clone instead of becoming the constructor defaults.

This is a shared APSResult bridge correction, used by Boost and other APS plugins;
it does not change Boost's dose formulas or add a second command path.
The only executable change in this closeout is preservation of those two fields.
Other production edits clarify live-calculation comments and registration scope.

## Execution status and phone check

No tests, new CI run or APK build were started for this patch, as requested.
The preceding diagnostic APK was reported as working on the phone. The latest
TDD fallback and this clone correction still require their own current APK check.
Earlier successful suite counts do not validate these later changes.

On the requested virtual-pump setup, select Boost V6 and the intended standard
Loop mode. Check that the Loop records Boost as the request source and that an
allowed TBR/SMB request appears as an enacted virtual-pump history entry. Exercise
absolute and percentage TBR representations if both are used. A cycle may correctly
request no SMB; only a permitted positive request is enqueued. Verify selection
and fresh calculations after restart/import as part of the phone check.
