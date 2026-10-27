# V1 offline dependency compatibility checkpoint

This patch checks consistency of caller-supplied V1 input snapshots and existing scalar diagnostics. It is an isolated KMP inspection utility, not a full adaptation of DetermineBasalBoost or a live AndroidAPS integration. It produces no therapy output. Dmitry's correction ordering and pump control remain unchanged.

The included JVM tests and existing KMP/APK workflow require verification. Real-world dosing remains disabled.
