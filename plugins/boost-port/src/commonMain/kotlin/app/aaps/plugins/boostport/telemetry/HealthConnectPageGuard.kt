package app.aaps.plugins.boostport.telemetry

/**
 * Pure KMP guard for paginated, read-only Health Connect snapshots.
 *
 * Health Connect can return incomplete pages. A repeated continuation token or
 * exceeded page budget is an incomplete result, never a zero-valued snapshot.
 * The Android adapter must discard collected records on either failure.
 */
internal class HealthConnectPageGuard(private val maxPages: Int = 20) {
    init {
        require(maxPages > 0) { "maxPages must be positive" }
    }

    enum class Next { COMPLETE, CONTINUE, REPEATED_TOKEN, PAGE_LIMIT }

    private val seenTokens = mutableSetOf<String>()
    private var pages = 0

    /**
     * Call once after successfully fetching each page. Null/blank signals EOF.
     * A continuation after the final allowed page fails closed.
     */
    fun accept(nextPageToken: String?): Next {
        pages += 1
        if (nextPageToken.isNullOrBlank()) return Next.COMPLETE
        if (!seenTokens.add(nextPageToken)) return Next.REPEATED_TOKEN
        return if (pages >= maxPages) Next.PAGE_LIMIT else Next.CONTINUE
    }
}
