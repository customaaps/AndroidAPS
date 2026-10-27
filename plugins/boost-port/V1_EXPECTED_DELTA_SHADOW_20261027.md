# V1 expected-delta offline port

Extracted the original 24 five-minute-block expected delta calculation from `DetermineBasalBoost.calculate_expected_delta` as a pure standalone KMP diagnostic, with fail-closed invalid input handling and tests. No dose or bolus value is returned; no APS plugin, pump, or preference wiring. This does not validate V1/V5 clinical parity. Dmitry dosing and ISF/Autosens order are unchanged.
