# Offline Boost V1 calculation status

The V1 dynamic-ISF, prediction-array and SMB-condition shadow implementations are now callable from one replay-only facade. The caller supplies a cycle-matched input for each stage; this facade is **not** the original `determine_basal` implementation, does not compute max-IOB dosing, does not produce an actionable `RT`, and cannot issue pump commands.

The archived source and its full profile/meal/IOB dependencies still need executable adaptation and source-to-source replay verification. A successful build alone does not establish dosing equivalence. No tests are requested for this commit.
