package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntryWithTime
import app.aaps.database.entities.interfaces.TraceableDBEntry

/** AutoISF algorithm component values calculated every 5 minutes. */
@Entity(
    tableName = TABLE_AUTOISF_VALUES,
    indices = [Index("timestamp")]
)
data class AutoIsfValues(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    /** Milliseconds since the epoch. */
    override var timestamp: Long,
    /** Accelerometer-based ISF adjustment factor. */
    var acceIsf: Double,
    /** Blood Glucose-based ISF adjustment factor. */
    var bgIsf: Double,
    /** Post-Prandial ISF adjustment factor. */
    var ppIsf: Double,
    /** Drift ISF adjustment factor. */
    var driftIsf: Double,
    /** Duration-based ISF adjustment factor. */
    var duraIsf: Double,
    /** Final ISF value after all adjustments. */
    var finalIsf: Double,
    /** Effective IOB threshold. */
    var iobThEffective: Double,
    override var utcOffset: Long = defaultUtcOffset(timestamp),
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = null
) : TraceableDBEntry, DBEntryWithTime
