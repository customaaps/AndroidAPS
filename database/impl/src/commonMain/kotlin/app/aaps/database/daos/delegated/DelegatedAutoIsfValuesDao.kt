package app.aaps.database.daos.delegated

import app.aaps.database.daos.AutoIsfValuesDao
import app.aaps.database.entities.AutoIsfValues
import app.aaps.database.entities.interfaces.DBEntry

internal class DelegatedAutoIsfValuesDao(
    changes: MutableList<DBEntry>,
    private val dao: AutoIsfValuesDao
) : DelegatedDao(changes), AutoIsfValuesDao by dao {

    override suspend fun insertNewEntry(entry: AutoIsfValues): Long {
        changes.add(entry)
        return dao.insertNewEntry(entry)
    }

    override suspend fun updateExistingEntry(entry: AutoIsfValues): Long {
        changes.add(entry)
        return dao.updateExistingEntry(entry)
    }
}
