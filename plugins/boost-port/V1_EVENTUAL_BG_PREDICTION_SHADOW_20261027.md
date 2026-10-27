# Legacy V1 BGI, deviation, naive eventual BG — offline extraction

This change ports the original DetermineBasalBoost BGI, deviation fallback, naive eventual BG and delta-acceleration scalar expressions into independent KMP diagnostics. It does **not** implement full predictions (IOB/COB/ZT/UAM arrays), final branch decisions, RT output, or any insulin commands. Additional tests were staged but not run; commit uses [skip ci]. Dmitry dose logic and ISF/Autosens order are untouched.
