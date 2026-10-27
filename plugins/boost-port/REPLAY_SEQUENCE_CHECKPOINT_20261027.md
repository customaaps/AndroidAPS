# V1/V5 replay sequence — isolated checkpoint

A non-dosing KMP sequence inspector now executes the already-staged paired
V1 scalar diagnostics and V5 pure-engine shadow invocation over caller-supplied
ordered frames. This is not stateful V5 replay: the prior state must be supplied
for every frame, so cross-cycle state transitions and full V1 dose parity remain
unverified. Invalid or out-of-order frames fail closed. No active APS registration
or pump commands have been introduced.

The test suite and APK build are requested after this patch. A passing build
cannot establish clinical safety, live parity, or readiness for automated insulin
delivery. Dmitry's dosing and ISF/Autosens order stay unchanged.
