# Apex TruCare III

Android pump driver integrated into the full and pump-control builds through the existing pump-driver project discovery. Select **APEX TruCare III** in Config Builder and enter the pump's eight-digit serial in its settings. Selecting this plugin and enabling Loop remain explicit user actions.

Source: `Vlad747kja/AndroidAPS`, branch `3.4.2.6-Boost_V6_261026`, pinned commit `ae43a2f1d2879a1f72b02108020c0e66edc1f620`. Original author attribution is retained. The source includes Bluetooth reconnect/password handling, pump history synchronization and interrupted/uncertain bolus tracking. Packet formats and pure driver decisions are preserved.

The integration replaces Dagger and the XML fragment with Metro bindings and a Compose screen/settings. It publishes typed pump amounts through StateFlow, uses native notifications and BolusProgressData, and bridges the existing transport workers to suspend PumpSync APIs. Limits enter the current cU-aware PumpPluginConstraints boundary; IU limits are not reapplied to native pump amounts. TDD amounts are converted to IU separately because that PumpSync method still takes doubles.

History reconciliation can correct bolus amount/type while preserving an existing AAPS timestamp. `updateExistingTimestamp` defaults to true for all other callers. Apex passes false when importing pump history because its minute-resolution timestamps must not replace the recorded delivery time.

Extended boluses remain unsupported, as in the source driver, and are not advertised as a capability. Fill-history reading remains an optional engineering diagnostic and does not insert therapy records.

## Validation status

No build, tests, CI or device execution was performed for this port at the owner's request. The 3.4 source test files are retained unchanged under `upstream-tests` with a `.source` suffix for reference; they are not runnable coverage for the Metro/Compose integration. The source commit is the reference for its previous validation history, not a validation claim for this branch.
