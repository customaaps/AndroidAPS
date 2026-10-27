# Unified read-only V1/V5 integration checkpoint

One-call offline integration of V1 cycle provenance, the low-glucose branch
classifier, stateful V5 replay, validated result envelope, and combined
protective diagnostic summary. All inputs are caller-provided. The component
does not participate in Dmitry's production calculation, does not implement
AndroidAPS APSResult/RT, and cannot issue a therapy command.

Scope deliberately excludes enabling the V1 source determine_basal engine,
pump registration, V6 production adaptation and safety validation. There is
no guarantee of compile/test success until separately verified. Tests are
not run under the user's explicit instruction.
