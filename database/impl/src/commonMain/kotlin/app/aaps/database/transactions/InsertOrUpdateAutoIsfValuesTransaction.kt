package app.aaps.database.transactions

import app.aaps.database.entities.AutoIsfValues

/**
 * Batch variant for AutoISF values. Runs all inserts/updates inside
 * one Room transaction so the repository emits a single change event for the whole batch.
 */
class InsertOrUpdateAutoIsfValuesTransaction(private val autoIsfValues: List<AutoIsfValues>) :
    Transaction<InsertOrUpdateAutoIsfValuesTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        val inserted = mutableListOf<AutoIsfValues>()
        val updated = mutableListOf<AutoIsfValues>()
        for (value in autoIsfValues) {
            val existing = if (value.id == 0L) null else database.autoIsfValuesDao.findById(value.id)
            if (existing == null) {
                database.autoIsfValuesDao.insertNewEntry(value)
                inserted += value
            } else {
                database.autoIsfValuesDao.updateExistingEntry(value)
                updated += value
            }
        }
        return TransactionResult(inserted, updated)
    }

    data class TransactionResult(val inserted: List<AutoIsfValues>, val updated: List<AutoIsfValues>)
}
