# SHA-verified Boost V6 source snapshot

Source repository: `agniabakush/AAPS-3.4.2.3_Dmitry`
Source branch: `3.4.2.6-Boost_V6_261026`
Source commit (captured): `5f0f9656de046cdab4c5e5616c0f08fa460a19c9`
Target repository: `agniabakush/AndroidAPS`
Target branch: `dev`
Target subtree: `plugins/boost-port/legacy/`

This is a byte-for-byte blob inventory, not evidence of integration with AndroidAPS dev or safety for automated insulin dosing.

Verified matching SHA-1 Git blobs: **114 / 114**.
Source files: **39** Kotlin sources, **4** ML JSON model/metadata assets, **71** Kotlin regression tests.

Source Git blob SHA, followed by the original relative path. The target has each file at `plugins/boost-port/legacy/<original path>`.

```text
c34a3247177620bed5e06898821db74796eb717a  app/src/main/assets/boost/hypo_risk_meta.json
ada7ad9f8907bca1a4d7b50a2cb086a60a8dea49  app/src/main/assets/boost/hypo_risk_model.json
9cb089af1670d7468d1583e4250b7cb4de383fc2  app/src/main/assets/boost/meal_likelihood_meta.json
3f28e7c798e31a0c68173f842f6bbf529653a365  app/src/main/assets/boost/meal_likelihood_model.json
0a9c30f87944bdc2dae4d66759e3247a4e56b58a  core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/OapsProfileBoost.kt
b213474febf9cdc13724b3ba12fd50bfb7ec5fbf  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/BoostDosingPreferences.kt
ece9e8f4d81663a05f9aa42f2ba3f9983d2faa3d  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostIsfShadow.kt
3cb022777238019a2f6ae52c33ff1236a9d5fd05  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostMealModel.kt
ca8991eda752393ddf9739780a801ebf4166814f  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostMlFeatureBuilder.kt
4775125e345eb41e2b613fb38142540d69c272fa  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostRiskModel.kt
230550ef8e5b07af25c65ccb81f739be669e081e  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostTddSettingTransition.kt
1a6ee787120bdd1e8a209a0e325dfe1b859cd18b  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1SafetyRules.kt
a0787acec53c0411b5579f5b6ddc39e712d1b2da  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostVwaTddShadow.kt
9805031e54ab7f7bb3937ca3c29a66cd9bfda023  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/DailyStepHistoryTracker.kt
65b56ebb9a9db6d5b5cc3a8c020f152dfc2fd0a1  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/DetermineBasalBoost.kt
3399d5671804df9fbdbddfb2d656795fe81d97ff  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HealthConnectHrIngest.kt
dfe2f1707579032980c41c466a142efd6ef507f2  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HealthConnectPermissionActivity.kt
0987779e858ab8ccb9fd9871ab4ab2151380cda6  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HealthConnectPrivacyActivity.kt
0b7cb43d40988923f4e554c652b468949f092b65  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HealthConnectStepsIngest.kt
d7662456d7f3c4d6ad04c38f838e91b0ce688657  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HrActivityCalculator.kt
3cf94c20f831d1f4cde72fca781212e6fe72cda3  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HrSourceResolver.kt
0d1a6426a2e1d7781ddbaf342812dfa27b3bf552  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/MealTimeLearner.kt
ada0ef76711816f8d3d0825d7c193a5feca019b7  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/NightWindow.kt
e33fba201719c3400b68b9c27a3453a2d85537ab  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/OpenAPSBoostPlugin.kt
6e0a4ddbb3649a95d02915ca861902019760449e  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepHistoryTracker.kt
a3d1ce669e4c7814799ae870c3d4aa7b3f15bd83  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepStateDetector.kt
3ee9a6000135192b4c34a610b3cad52a6c428a4d  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/StepFeed.kt
18e9d5a9d2c8243aef5c1867486a772b974e54ac  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/StepService.kt
bb3617a8ffa32fcd3cebe6584731db363e21cc3f  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/StepSourceResolver.kt
99c8f90c8fbcdfa85a44df5f1f1e2b6f146db13a  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/WearStepSource.kt
2761b2df6d3b2d2d8eedb5cfba9b12de31e95dd6  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostTing/TingPlanner.kt
0323cd0116f866f82f0a29e77453593615c4142b  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/AggressionBudget.kt
5de66b5013d44cbf63a293da24fb234362787536  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostHistorySync.kt
630c5b0f85ebed2dfd6ed3e814591e5b1cb895a0  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostV5AutoConfig.kt
4bee66dfb0f64a836e9999c5544f96c838613ede  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostV5AutoConfigApply.kt
1baa8290ce84cac4e749fd0c1665d4f88ed21d9e  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ConfirmTrancheController.kt
16544e5d7d9d12d81a4c9955e4b12d7e68f97fec  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/DetermineBasalBoostV5.kt
2b3f139d7a0172ea27e764d868b8ea08c8b86393  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealActionMultiplier.kt
c2aa8b9a6699ccbcf4c79883610de4b35297a726  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesis.kt
b3c2dc23961d8f42978ac7dff21fb7559a6510af  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealSignalScore.kt
082573a05735f83323a78cae757c5661cd430918  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/OpenAPSBoostV5Plugin.kt
751a3c2afde69130776e90405fe030990da8d047  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/SafetyGates.kt
26fa560c847a6bc37d4055deb9236f1c8bc6cfdb  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/V5StateStore.kt
9fafaeb4712d0cd2b97b73be754ae964fa25bc7c  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/ActivityNightGateTest.kt
5bbec188fca5641fbfc0ddabe0a020e4f3de9030  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostAutosensCoordinationTest.kt
c3a096cdf5bc1df1c847e07246e8e17a7868c150  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostAutosensIsfTest.kt
fb6e62f8a12dce178e2b46e383b437b14ad6ceea  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostBaseSensitivityTest.kt
aa00a0b906bca43a14e36fca94d9489ea937a5b1  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostEngineScopeTest.kt
4ed9672e5bc29675369b82fb96f25b12f331ac50  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostMaxIobMaskBypassTest.kt
b814a580dc442dfed0dec37c4b4ba69e20a4589b  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostMlRingBufferStalenessTest.kt
51affa1a969dd7d2e7e9b8757cc6a1e747ea6376  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostNightGateTest.kt
8623daff681f59369a8fbde3ec1b060568813782  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostRecoveryTtCancelTest.kt
8f81219eed8090ebdaea22674dc6c9a94bb6a7d9  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostSensitivityDefaultsParityTest.kt
e18fc6d9ee2d3a0771d684495dd2381e99c8490c  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostSensitivityRatioSelectionTest.kt
e830a3172ff450d28e4e8b2b33d801ca67070d2b  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostTddSettingTransitionTest.kt
05ec61f126acf7da6dd9bc71efcc719a6f7b73b0  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1SafetyEngineTest.kt
3e59880853fa5868fadce6e71cc70005bef5af85  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1SafetyRulesTest.kt
8d7f2fd430ba4f36e63924c74d35033ed8c27077  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostVwaTddShadowTest.kt
2b1d29b2dc1c795f36d3497ae11fae8253745c63  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/CalibrationBlockTest.kt
3f44d73725f09f1aabf647f152a748846bf7a031  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/DailyStepHistoryTrackerTest.kt
f057f77af83d2fe3907060033bc34a1ed05e0f1e  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/DynIsfTddGuardTest.kt
320b3c3febed3ad85e757c7ef4cd5ce72f6d0d80  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/ExerciseRegressionTest.kt
4f71606e55029ff54d205d583350e685f6d011e0  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/HealthConnectStepsSourceTest.kt
acfcac151433ed9e719110dfe28e5c5508dce2c4  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/HrActivityCalculatorTest.kt
d09e1170ce2ae128adc96797516d12bd664b6beb  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/HrSourceResolverTest.kt
3af716509ee02ec9fafd4564723277cf162750b7  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/IntradayActivityFactorTest.kt
c2d648df4388ecee8a3c4463c14979cf1062f40f  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/IntradayStepBankTest.kt
595c1518681349df4863ff73d11e071ae15f9dc4  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/MealSessionRecordingGateTest.kt
5caa1e6704624a82d879ada34df45d72a4c398e0  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/MealTimeLearnerTest.kt
d46e6a7c5810a6b37c5c93418181fd5580f9c19f  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/MealTimeNightFilterTest.kt
b8ae8a3e424b60b70fefe100041e21768c935698  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/NightWindowTest.kt
6af971206a8064ae7c9119fad2c74542f0eca819  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PostExerciseRecoveryTest.kt
d87cf34945d3f0e7ee604dfdcb5c984434c6ba93  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PostRescueMealStateCapTest.kt
0f95b0722b5b6c6c534c1a8c40aec848aa60d2dc  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PostRescueReboundScaleTest.kt
e895257e3949eb3d363f4a8d34daead527511728  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PostRescueTightRampTrialTest.kt
d4dabd00ca203c27587e77dda40fbb28640a6e07  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PreMealTargetSafetySeamTest.kt
9fe6dfa35eabf447037d7ba367610271d4a98d5a  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PreMealTargetSafetyTest.kt
b51757c451374c0e75a2e03cf441d5112cae38f8  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PrimerTbrActionSafetyTest.kt
0510c38992b2693f4bd9e7b7b1eefe2c5a9c37cf  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/PrimerTempSafetyTest.kt
b8dbc8f3c5539b2e404d1f5d4e2695d90ef9474b  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepBoundaryHoldSafetyTest.kt
376ab0b0c98d00bbcb2f0a948275fca1dba49421  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepBoundaryHoldTest.kt
e89b35923e8d32ab8efc144ac6ce03cfe89f5a6b  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepLumpWakeTest.kt
bf9ab79ac9a354f46fbff919faf75729d8ebaab9  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepResumeWakeTest.kt
d4e4b40fb4e301689e13e2f7549e266eb5143a66  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/SleepWindowLearningTest.kt
180e2be371bd3522b79c07b2ef38aa16a6fd60a2  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/StepFeedTest.kt
aa03d794dbd2f37f1c90fa5d574b26ae96ec0dcf  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/StepServiceTodayTest.kt
22800c851a9dfb0a4d84428e12f6e6b3ba9b8aaa  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/StepSourceBridgeTest.kt
fb465081a34a1a48d0a8b08d24da9d741dfd8fea  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/V5ExerciseInputsTest.kt
64d8479b14398abf65b97516b4328154b3384b48  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/V6FallbackSmbSafetyTest.kt
c429406f5187866da1bd8eec87a867878067a494  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/V6StandDownFallbackTest.kt
74af307755ed9eac6c4530a5ee1db4803c3e9314  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoost/WearStepSourceTest.kt
bf84308104c642946f3f1de5cfe1668203cf1133  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/AggressionBudgetSensitivityTest.kt
4a8277d4a0829ab8966b6be100f11fe13f0cf639  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostDosingPreferencesTest.kt
1817d0dbf35882485956b63c0e41cbe704bf83bf  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostHistorySyncTest.kt
dae4a54f539059da777a63ad46a4dba9f883fcad  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostHistorySyncWindowTest.kt
f9fbd7797057801051071e4ecb7e3df235a74613  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostV5AutoConfigTest.kt
aa839212a548709990786ee04f255b8508af1775  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/BoostV5RedriveTest.kt
5bc17fdecc6e2adf7cd995f7372bdb53c28807f5  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ComposedFloorShadowTest.kt
9343c605169f8a8cd0e54ca909471cf17ece5709  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ConfirmTrancheControllerTest.kt
0be00ac0d2cebdcd12fedddd30f8ddd951126713  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ExerciseGateDecideTest.kt
4bc6b519d23ce35bbbc4d4d7a53ce6a9a32ef80a  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/HypoCautionDirectionTest.kt
aa962d22a7f5005812fa0baf406d99175eef0f8f  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealAnnouncedTest.kt
757ff7e0283d6dd4f5df1107cd1ba3b1ef292cfe  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesisAgeTickTest.kt
0464726e77cd3344e7461e891bc537e5402b854e  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesisDoseGateTest.kt
f017d82ad9f88c821e22094984727db07cb4fcd0  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesisEarlyConfirmTest.kt
ccb5b94e55b61d97854cc077fe72236226e97555  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesisFastConfirmTest.kt
2d76eeb8de3d58b1dccfc3e931a68da0a0c2ccd2  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesisFix7Test.kt
043ba3f8a0234e400882a01bcad6c605501dccc6  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealSessionLockTest.kt
37be8e2d137f6708511e601ba3ef4a9a6c817b8a  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/PrimerSafetyGateTest.kt
72524c8f5e53558d357447ffd3635857296dc090  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/PrimerTest.kt
a7efdce9b6a9b8089e505c0478ce6002370916d9  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/SafetyGatesDecelBrakeTest.kt
c357cdd62af5c2acdf6d3b49bce1ca0f80a282d8  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/ScoreStreakCadenceTest.kt
7b97d4ed0ced8ec79f94590ac6dc08824db2f2d0  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/StaleMealStateTest.kt
30cd61766923c8a6102686425085891c8c67341f  plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSBoostV5/VelocityBudgetFloorTest.kt
```
