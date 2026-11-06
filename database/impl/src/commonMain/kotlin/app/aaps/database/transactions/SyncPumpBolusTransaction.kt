package app.aaps.database.transactions

import app.aaps.database.entities.Bolus

/**
 * Creates or updates the Bolus from pump synchronization
 */
class SyncPumpBolusTransaction(
    private val bolus: Bolus,
    private val bolusType: Bolus.Type?, // extra parameter because field is not nullable in Bolus.class
    private val updateExistingTimestamp: Boolean
) : Transaction<SyncPumpBolusTransaction.TransactionResult>() {

    /** Retain the original two-argument constructor for Java as well as Kotlin callers. */
    constructor(bolus: Bolus, bolusType: Bolus.Type?) : this(bolus, bolusType, true)

    override suspend fun run(): TransactionResult {
        bolus.interfaceIDs.pumpId ?: bolus.interfaceIDs.pumpType ?: bolus.interfaceIDs.pumpSerial ?: throw IllegalStateException("Some pump ID is null")
        val result = TransactionResult()
        val current = database.bolusDao.findByPumpIds(bolus.interfaceIDs.pumpId!!, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
        if (current == null) {
            database.bolusDao.insertNewEntry(bolus)
            result.inserted.add(bolus)
        } else {
            if (
                (updateExistingTimestamp && current.timestamp != bolus.timestamp) ||
                current.amount != bolus.amount ||
                current.type != bolusType ?: current.type
            ) {
                if (updateExistingTimestamp) current.timestamp = bolus.timestamp
                current.amount = bolus.amount
                current.type = bolusType ?: current.type
                database.bolusDao.updateExistingEntry(current)
                result.updated.add(current)
            }
        }
        return result
    }

    class TransactionResult {

        val inserted = mutableListOf<Bolus>()
        val updated = mutableListOf<Bolus>()
    }
}