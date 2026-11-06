package app.aaps.database.daos

import androidx.room.Dao
import androidx.room.Query
import app.aaps.database.entities.AutoIsfValues
import app.aaps.database.entities.TABLE_AUTOISF_VALUES

@Dao
internal interface AutoIsfValuesDao : TraceableDao<AutoIsfValues> {

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE id = :id")
    override suspend fun findById(id: Long): AutoIsfValues?

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES")
    override suspend fun deleteAllEntries()

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES WHERE timestamp < :than")
    override suspend fun deleteOlderThan(than: Long): Int

    @Query("DELETE FROM $TABLE_AUTOISF_VALUES WHERE referenceId IS NOT NULL")
    override suspend fun deleteTrackedChanges(): Int

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp >= :timestamp AND isValid = 1 ORDER BY timestamp")
    suspend fun getFromTime(timestamp: Long): List<AutoIsfValues>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp BETWEEN :startMillis AND :endMillis AND isValid = 1 ORDER BY timestamp")
    suspend fun getFromTimeToTime(startMillis: Long, endMillis: Long): List<AutoIsfValues>

    @Query("SELECT * FROM $TABLE_AUTOISF_VALUES WHERE timestamp > :since AND timestamp <= :until LIMIT :limit OFFSET :offset")
    suspend fun getNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): List<AutoIsfValues>
}
