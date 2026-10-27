# Additional General settings

Requested alongside the Boost port; no Boost V2 module is transferred.
Both new Android switches are in **General**, are off by default and have
English/Russian labels and summaries. They have no remote sync declaration.

## Ignore BG source

`BooleanKey.ApsIgnoreBgSource` (`ignore_bg_source`) skips the source's
advanced-filtering capability requirement in `SafetyPlugin`. This permits
the existing SMB-always/after-carbs choices to work with other BG sources.
It returns the incoming constraint unchanged, preserving a denial from another
constraint. Closed-loop permission, CGM quality/freshness and dose limits are
handled by their existing checks.

The source branch's related `ApsBoostAllowAllBgSources` option was used only
by its V2 plugin. This requested option is implemented independently at the
target's common source-capability check; it does not import V2 or its default-on
setting. Existing `boost_allow_all_bg_sources` preferences are not reinterpreted
as opt-in to a broader target constraint.

## Disable new version checks

`BooleanKey.DisableUpdatesChecker` retains the original `skip_updates_check`
storage key and off default. As in source `3.4.2.6-Boost_V6_091026`,
`VersionCheckerUtilsImpl.triggerCheckVersion` returns before loading the
definition or posting warnings and clears this build's stored expiration.
`VersionCheckerPlugin` skips its expiration restriction when enabled and
preserves the incoming max-IOB constraint. Other constraints keep their limits.
Turning the option off restores normal checking. The summary explicitly states
that the option also disables the version expiration restriction.

Targeted tests exercise source filtering on/off, preservation of an incoming
denial, open-loop SMB denial, version-expiration on/off, preservation of an
existing max-IOB limit, and skipping definition loading/notifications while
clearing the stored expiration. The pre-existing constraints regression tests
are run with these additions.

Previously requested engineering-mode and SMS-OTP switches are retained.
The Boost inspection plugin remains hidden and disabled; these General
settings do not register the standalone replay engine as APS.

## Local verification

All 64 tests in `SafetyPluginTest` (23), `VersionCheckerPluginTest` (2) and
`VersionCheckerUtilsKtTest` (39) passed with zero failures/errors/skips.
`:app:assembleFullDebug` passed in the same invocation. APK signature
verification passed; all four requested switch storage keys were found in DEX,
the test-reference namespace was absent, and shipped model bytes matched source.
The APK at this checkpoint was 259,286,417 bytes, SHA-256
`5dab08c6a2459194d7771b8c44f363fd6adc0651f93e141351d94d28e68b2bde`.
The earlier model/replay checkpoint's APK is superseded by this build.
No device run, commit, push or remote CI is claimed.
The subsequent [V6 override and Russian settings checkpoint](V6_FINAL_OVERRIDE_REPLAY_20261027.md)
supersedes this artifact and its test/build stage.
