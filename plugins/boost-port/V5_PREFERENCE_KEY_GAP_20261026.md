# V5 preference key gap — 26 October 2026

This is an inspection finding, not an active preferences migration.

## Source and target

Original archived `BoostV5AutoConfigApply.kt` under
`agniabakush/AndroidAPS:dev/plugins/boost-port/legacy/` uses eight
`DoubleKey` enum members:

- ApsBoostV5Aggression
- ApsBoostV5HypoCaution
- ApsBoostV5ConfirmedCapU
- ApsBoostV5CommittedCapU
- ApsBoostCumulativeSmbCap60Min
- ApsBoostMaxIob
- ApsBoostBolus
- ApsBoostV5PrimerCapU

Inspection of the target branch's
`core/keys/src/commonMain/kotlin/app/aaps/core/keys/DoubleKey.kt`
found none of these symbolic declarations. The staged
`BoostV5PreferenceKeyMapAudit` checks names supplied by a caller, and
**does not create keys or verify real key storage/serialization**.

## Work remaining

- Compare source and target key identifiers, units, limits, defaults and
  simple-mode behavior across the entire preference/key catalogue.
- Map persisted state and versioned migration semantics without modifying
  existing user settings or live APS decisions.
- Evaluate dependencies and test migration behavior, including mmol/L
  defaults, user-tuned overrides, and historical defaults.
- Leave the Boost inspection plugin hidden/forcibly disabled and keep
  Dmitry's Autosens/ISF correction order unchanged.

No active dosing keys were introduced in this change. Boost V2 is excluded.
Clinical safety is not established by a green CI build.

## Exact original V6 key declarations recovered

Source: `agniabakush/AAPS-3.4.2.3_Dmitry` branch
`3.4.2.6-Boost_V6_261026`,
`core/keys/src/main/kotlin/app/aaps/core/keys/DoubleKey.kt`.
The following values are quoted from source enum constructor arguments;
columns are **persisted string ID, factory default, minimum, maximum**.
Every declaration uses `defaultedBySM = true`.

| Symbolic member | Persisted string ID | Default | Min | Max |
|---|---|---:|---:|---:|
| ApsBoostV5Aggression | `boost_v5_aggression` | 1.0 | 0.7 | 1.6 |
| ApsBoostV5HypoCaution | `boost_v5_hypo_caution` | 1.0 | 1.0 | 2.0 |
| ApsBoostV5ConfirmedCapU | `boost_v5_confirmed_cap_u` | 2.5 | 0.0 | 7.5 |
| ApsBoostV5CommittedCapU | `boost_v5_committed_cap_u` | 0.5 | 0.0 | 2.5 |
| ApsBoostCumulativeSmbCap60Min | `boost_cumulative_smb_cap_60min` | 10.0 | 0.0 | 10.0 |
| ApsBoostMaxIob | `boost_max_iob` | 1.0 | 0.1 | 12.0 |
| ApsBoostBolus | `boost_bolus_cap` | 2.5 | 0.1 | 10.0 |
| ApsBoostV5PrimerCapU | `boost_v5_primer_cap_u` | 0.0 | 0.0 | 2.5 |

These actual persisted strings must not be replaced with the enum member
names. This is a **documented compatibility map**, not a request to copy
dose-cap settings into active Dmitry preferences. A future integration
requires reconciling the target `DoubleKey` architecture, translations,
Simple Mode, migrations, safety limits and independent validation first.

No tests/CI launched for this source comparison.
