# Boost V6 selection and lifecycle integration

Target: `Vlad747kja/AndroidAPS:nigthscout-dev-boost-port-20261026`.
Base: `87de9547122084ab5bd2699e8221eb7db3c8c907`.

The user explicitly requested connecting Boost after reporting device checks of
application startup, permissions, settings import and data sources as successful.
This revision removes the temporary forced-disable/hidden-list gate from the live
APS adapter. Boost V6 appears in Configuration → APS in APS builds and as a master
algorithm choice in clients, matching the existing SMB visibility rules. It does
not force itself enabled, become the default, or replace the user's current APS.
No source preference defaults or imported settings are rewritten by activation.

The real app graph already registers OpenAPSBoostPlugin under key 236. Its existing
calculator executes V1 → V5 → V6 and publishes the standard BOOST APSResult. The
standard Loop persists that result, reapplies basal/bolus constraints and owns all
pump commands. No command-queue or pump-delivery API is introduced into Boost.
Global SMB disable is also checked directly before preparing the V6 request;
calibration, fallback and constraint denials remain effective. Standard mode,
objective, pump and development-build restrictions remain in force.

The lifecycle fix closes a specific import gap: replacing the generation token
alone formerly allowed a new invocation to create an owner during import. The
adapter now requires a started lifecycle and a closed import window, checks the
same generation at owner creation/publication, and tracks profile-switch metadata
inside that ownership boundary. Stop blocks new invocations until restart; restart
during import cannot reopen calculation early. Calibration observer identities
are checked under the owner lock, so retired collectors cannot change new state.

An invalidated owner is detached and published result/time cleared before platform
cleanup. Cleanup failures are logged without retaining the retired owner. The
calculator closes its storage/cancels its active cycle first, then releases the
activity lease even if Nightscout cancellation fails. History repair detaches its
client before cancellation so a failed cancellation is not repeated indefinitely.
Imported persisted state remains available to the fresh owner.

Tests cover actual user-controlled enable/disable, real application registration,
APS-result/constraint publication, SMB disable/denial, the import window, stop and
restart, suspended stale results/notices and cleanup failures. Existing V1/V5/V6,
source oracle, native-cadence and standard Loop suites remain enabled in CI, along
with fullDebug APK compilation.

## Verification evidence

- [CI 40688315976](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40688315976),
  source `94cf81b8c2b80dc13f9c6b1d72faddf610d2f361`: **1401 Boost JVM tests**
  passed with zero failures/errors/skips, and Boost KMP assembly passed. This run
  subsequently failed APS test fixtures missing native-cadence and text mocks.
- [CI 40691809200](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40691809200),
  source `618593a337f8975f3587cf08debe5f624ace710c`: **135 Android host tests**
  passed with zero failures/errors/skips: Boost adapter 19, preference keys 3,
  standard Loop 40, autosens 46, post-calculation 13, Loop cadence 5 and SMB
  glucose-status 9. Real Boost app registration also passed; the catalog snapshot
  failed because it omitted existing inspection entry 235. **fullDebug APK
  compilation and APK artifact publication passed** independently of that test.
  An exact-parent and changed-file guard proved production and JVM sources
  unchanged from the first run; only test fixtures/catalog and CI differed.
- [CI 40695283893](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40695283893),
  source `24029072f04782d394bd920f3df0a3c894cc7c14`: corrected catalog and extended
  **two real-graph/catalog tests passed** with zero failures/errors/skips. The
  complete run passed. Its exact-parent diff guard passed, proving
  production, JVM and Android host sources unchanged from the previous run.

Across the unchanged production sources, **1538 tests passed** (1401 JVM, 135
Android host and two application graph/catalog tests). XML artifacts confirm zero
failures, errors and skips in those successful suite executions.

The working-branch transfer retains the validated source files and restores the
complete CI workflow, including every suite above and APK compilation, for future
changes. Temporary workflows that reuse unchanged coverage stay in validation
history. APK: [artifact 12819570686](https://github.com/Vlad747kja/AndroidAPS/actions/runs/40691809200/artifacts/12819570686).

After installing this revision, select Boost V6 explicitly in Configuration → APS.
The application's selected Loop mode and SMB settings still determine permitted
actions. Compilation and automated tests do not establish clinical performance;
device testing of the newly selectable Boost calculation/dosing path is separate
from the already reported general device checks. V2/V7 remain excluded and the
older General inspection plugin stays hidden and disabled.
