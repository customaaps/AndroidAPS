# Boost 6.2 automatic configuration bulk transfer

Source: `tim2000s/Boost-in-AAPS_3.4:dev` at
`3f75d6a36c84e048c5ddf8a5dfce935184842d11`. Target:
`Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.

This block brings the remaining 6.2 automatic-configuration policy into the
existing sequential KMP APS owner: guards on all loosening, the neutral automatic
aggression ceiling, computed-cap ownership, zero-primer preservation, weekly
primer delivery route and AEC/FCC switches, and personalized tranche thresholds.
The original 14/28-day windows, weekly cadence, migrations, deadbands, repeated
confirmation and movement bounds remain in the source-adapted pure helpers.

An additional written-value ledger distinguishes auto-config values from manual
edits, including edits back to factory defaults. Both that ledger and the initial
summary are owned/exported plugin keys. All knobs, switch decisions, provenance,
baseline/pending ledgers and clocks commit in the existing single storage edit.
Cancelled reads and concurrent settings changes cannot commit a staged batch.
Manual switches use the source user-set marker; a manually owned primer route is
also kept. The original 90 preference defaults/bounds are unchanged.

Optional RT fields retain the active V5 state for future history. The history
reader queries 45 days in daily ranges, keeps native-cycle confirm onsets, and
derives the threshold from the original episode labelling, fitted probability,
evidence minimums and Youden grid. Old results with no fields supply no evidence.
Missing history leaves the current threshold in place. Tightening may apply;
automatic lowering remains disabled, with no caller override. Tranche activation
and the immediate fraction remain user controlled. Existing finite release bounds
are retained in the controller.

Four exact upstream blobs are separately archived under `port-source/6.2.0` and
hash checked by the JVM oracle generator. The original 114-file archive is not
modified. The oracle adapts package/key bindings and omits the Android preference
ledger; it preserves the original pure source policy. Target behavior is stricter
for manual edits back to recorded factory values and exposes no lowering hook.

Validation before CI: 126 local pure Kotlin/JVM tests passed, including the
synthetic replay fixture, 1440 onboarding comparisons and 324 repeated redrive
steps. The added owner/history tests cover restart/manual ownership, switch/route
confirmation, corrupt provenance, daily boundaries, native onsets, cancellation
and old/new RT JSON. The completed CI results are recorded below.

The Boost APS plugin stays hidden and forcibly disabled. Dmitry's Autosens/ISF
correction ordering is unchanged; V2/V7 remain excluded. Native CGM cadence and
global Loop scheduling are the next separate bulk transfer. Device validation and
clinical performance are not established by this work.

## Verified checkpoint

[CI 40675993285](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40675993285) passed 1399 Boost JVM tests,
46 autosens host tests and Boost module assembly at `285e9547`.
[CI 40679752693](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40679752693) passed the remaining 27 host tests
and fullDebug APK compilation at `6f6a6dc2`, after verifying production sources
were unchanged from the first checkpoint. In total: 1399 JVM and 73 host tests,
zero failures/errors/skips. XML reports explicitly include all twelve auto-config
owner cases, three tranche-history cases and four V5 elapsed-time cadence cases.
The final transfer restores the complete CI workflow and changes no tested
production/test source after these results.
