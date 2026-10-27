# V1 offline guarded diagnostic result

Joins legacy V1 CGM early-exit detection to the already staged offline forecast/result envelope. The result is returned only when clocks match, CGM input passes the legacy read-only gate and the forecast pipeline generated diagnostic curves. Rejected CGM readings never expose a forecast report.

This does not port full DetermineBasalBoost decisions, construct a real APSResult/RT, alter Dmitry dose logic or authorize insulin delivery. Regression tests are staged; the commit uses [skip ci].
