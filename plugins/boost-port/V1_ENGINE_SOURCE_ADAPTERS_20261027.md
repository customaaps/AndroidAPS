# Boost V1 source math adapters, offline only

This batch extracts two additional pieces of original archived DetermineBasalBoost into pure, platform-independent functions: the getIsfByProfile logarithmic calculation and enable_smb conditional precedence. Kotlin tests cover a nominal mathematical identity, nonfinite values and critical conditional ordering.

Neither object is connected to the live Dmitry APS path or preference state. The SMB branch match is *not* permission to deliver insulin. These adaptations are partial and do not replicate original V1 determine_basal, RT production or therapeutic behavior. Dmitry's ISF/Autosens calculation order remains unchanged.

Test files were committed. CI will be initiated for this commit; passing unit tests is not medical validation.
