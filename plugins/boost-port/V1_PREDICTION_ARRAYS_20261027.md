# V1 offline prediction arrays — integration checkpoint

This patch extracts the source V1 five-minute IOB, COB, aCOB, zero-temp (ZT) and UAM recurrence into a bounded read-only KMP calculation. It uses the previously ported logarithmic dynamic ISF helper and a separate unified V1 diagnostic wrapper.

The caller must supply derived absorption inputs (CI, CID, ACID, remaining CA time, remaining CI peak, deviation slope), IOB activity timeline and zero-temp activity. Computing these from AndroidAPS IOB/meal records, curve trimming, final aggregate guards, and complete DetermineBasalBoost decision/RT generation still require a subsequent port. This is not full clinical parity. No therapy output or pump access.

Regression tests were committed but **not run**, per instruction; [skip ci] avoids starting CI. Dmitry's live dose and ISF/Autosens ordering remain unchanged.
