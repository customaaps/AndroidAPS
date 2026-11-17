package app.aaps.plugins.source.libre2

internal data class Libre2UploadSummary(val current: Int, val history: Int) {
    companion object {
        fun from(batch: List<Libre2UploadRecord>) = Libre2UploadSummary(batch.size,
            Libre2LibreViewPayload.completed(batch.map { it.reading }).size)
    }
}

internal fun Libre2ExchangeSettings.withAcceptedUpload(batch: List<Libre2UploadRecord>, now: Long): Libre2ExchangeSettings {
    val summary = Libre2UploadSummary.from(batch)
    return copy(lastUpload = now, retryAfter = 0, tokenRefreshes = 0,
        status = if (summary.history > 0) "uploaded_history" else "uploaded_current",
        lastCurrentCount = summary.current, lastHistoryCount = summary.history,
        lastHistoryUpload = if (summary.history > 0) now else lastHistoryUpload,
        lastUploadResult = "accepted")
}
