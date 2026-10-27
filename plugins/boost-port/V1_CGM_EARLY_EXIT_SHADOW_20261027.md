# Original V1 CGM early-exit diagnostics

Extracted the first branch of legacy `DetermineBasalBoost.determine_basal`: CGM noise/calibration, glucose-age bounds and flat reading guard. The standalone KMP implementation mirrors its one-decimal minute-age threshold and reports only findings, never the original RT/temp-basal side effects. This is an isolated code port, not the full algorithm or a live dosing integration. New JVM tests are staged but not executed. Commit uses [skip ci]; Dmitry therapy, insulin ordering and pump control remain untouched.
