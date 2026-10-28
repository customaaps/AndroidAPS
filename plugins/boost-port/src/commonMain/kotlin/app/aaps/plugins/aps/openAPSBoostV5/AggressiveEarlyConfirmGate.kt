package app.aaps.plugins.aps.openAPSBoostV5

/** Source audit 16: the switch and the same fail-closed trailing-14-day gate as both floors. */
internal fun aggressiveEarlyConfirmAllowed(switchOn: Boolean, tbrBelow63Pct: Double?, tbrBelow70Pct: Double?): Boolean =
    switchOn && composedFloorAllowedByTbr(tbrBelow63Pct, tbrBelow70Pct)
