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
cancel the other. Verification is pending for this revision; the previous
activation totals do not cover it. User requested pushing this block before CI
completion. Device/clinical validation is not claimed. V2/V7 remain excluded;
Dmitry Autosens/ISF correction ordering and therapeutic defaults are unchanged.
