# Boost 6.2 current-cycle sleep and independent wake evidence

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
Base: `e42a0f8595be0aa607ffd2cfc646c4f94b688ca2`.
Source: `tim2000s/Boost-in-AAPS_3.4:dev` at
`3f75d6a36c84e048c5ddf8a5dfce935184842d11`.

The detector now runs before this cycle's night policy, activity preparation and
V5 inputs. Entering PRE_SLEEP can suppress SMB and the inactivity profile raise
on that cycle. Waking updates the night-policy decision immediately; the target's
existing boundary-exit hold and fixed Boost/clock-night windows remain effective.
The model meal likelihood supplied to sleep is from the last completed engine
cycle, as in current source, and resets with its calculator owner on stop/import.

Scope boundary: upstream 6.2 retires the old Boost start/end clock settings in
favor of its night/sleep eligibility model. This block preserves the target fork's
fixed window and does not claim that separate policy migration is complete.

Phone and watch cumulative counts remain independent through the sleep detector.
Wake evidence is the larger growth across the two series, rather than growth of
the larger day total, so a parked phone total cannot hide watch movement. The HR
plus steps rule works through the lie-in; HR alone and core-night guards remain.
Phone samples serialize under the source field name, old states without it load,
and snapshot copies protect both mutable series. Writes remain atomic with
history transitions and retain the existing cancellation/import owner boundary.

The start-of-cycle query blends phone steps with every sampler window at the
newest SC timestamp. Sleep/HR telemetry reuses that captured evaluation. The source
`sleepDbg` breadcrumb reports watch/phone lookback growth, combined 15-minute
steps, HR streak, wake-candidate minutes and lie-in state without another query.

Three exact upstream blobs are preserved under `port-source/6.2.0`: the detector,
OpenAPSBoostPlugin and seven original lie-in regression tests. Their Git blob
hashes are pinned by `tools/activity_sources.py`. The portable detector and source
tests use mechanical namespace/JSON/math adapters; the JVM oracle uses original
source arithmetic and org.json. The original 114-blob archive is unchanged.

Added coverage includes the original seven wake cases, real owner masked-watch
wake and multirow steps, legacy restore and defensive copies, failed persistence,
same-cycle PRE_SLEEP/inactivity/SMB and boundary-wake hold behavior. The two-day
source comparison now carries independent totals and restart serialization.

The workflow runs full JVM/KMP verification and Android host/app/APK checks on
independent matrix runners. No suites are removed and failure in one job does not
cancel the other.

## Verification evidence

[CI 40704509064](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40704509064)
passed at `8ecadafbf17e0ea0009183e27bf2a7401b8e18dc`: **1414 JVM tests**, zero
failures/errors/skips, and Boost KMP assembly. XML explicitly includes all seven
original lie-in wake tests, 17 sleep-owner tests, eight telemetry tests, 21 real
calculator boundary tests and the two-day source comparison with restarts.

The initial engine run found one extra final blank line against the byte-exact
activity generator; it was removed without changing Kotlin tokens. The next run
found a new test expecting the boundary-exit hold to expire too early. The existing
hold is ten minutes of sleep hysteresis plus five minutes at defaults. The test
now verifies continued suppression at minute 11 and reopening at minute 16. This
correction changed no production source.

The [Android host/app/APK job](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40702574697/job/122866028851)
passed at `b12d546c1d23af6ac3b70596609d7f1058377f39`: **137 tests**, zero
failures/errors/skips (APS 62, native CGM/Loop 73 and app graph/catalog 2), and
fullDebug APK compilation/publication. [APK artifact 12823638879](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40702574697/artifacts/12823638879).
The initial engine matrix job in that run failed the generator check described
above; engine validation completed successfully in CI 40704509064. In total,
**1551 tests passed** across the successful suite executions, verified from XML.
 Its production code differs from the verified engine revision only
by the removed final blank line, with identical Kotlin tokens; the other change
is the corrected JVM test; its validation workflow selects only the engine job
while Android verification continues independently. The working branch retains
the complete two-job workflow and all checked source/test files. Device/clinical validation is not
claimed. V2/V7 remain excluded;
Dmitry Autosens/ISF correction ordering and therapeutic defaults are unchanged.
