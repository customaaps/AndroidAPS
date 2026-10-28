package app.aaps.pump.apex

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.runBlocking

/**
 * Boundary for the driver's existing serial, blocking transport workers. Values entering PumpSync
 * are native pump units; the core converts them once using the profile at the event timestamp.
 * Do not call these methods on the UI thread or from the command queue to enqueue another command.
 */
@SingleIn(AppScope::class)
@Inject
class ApexPumpSync(private val delegate: PumpSync, private val profileFunction: ProfileFunction) {
    fun expectedPumpState() = runBlocking { delegate.expectedPumpState() }
    fun connectNewPump() = delegate.connectNewPump()

    fun addBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: BS.Type, pumpType: PumpType, pumpSerial: String) =
        runBlocking { delegate.addBolusWithTempId(timestamp, PumpInsulin(amount), temporaryId, type, pumpType, pumpSerial) }

    fun syncBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: BS.Type?, pumpId: Long?, pumpType: PumpType, pumpSerial: String) =
        runBlocking { delegate.syncBolusWithTempId(timestamp, PumpInsulin(amount), temporaryId, type, pumpId, pumpType, pumpSerial) }

    fun syncBolusWithPumpId(timestamp: Long, amount: Double, type: BS.Type?, pumpId: Long, pumpType: PumpType, pumpSerial: String, updateExistingTimestamp: Boolean = true) =
        runBlocking { delegate.syncBolusWithPumpId(timestamp, PumpInsulin(amount), type, pumpId, pumpType, pumpSerial, updateExistingTimestamp) }

    fun syncTemporaryBasalWithPumpId(timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean, type: PumpSync.TemporaryBasalType?, pumpId: Long, pumpType: PumpType, pumpSerial: String) =
        runBlocking { delegate.syncTemporaryBasalWithPumpId(timestamp, PumpRate(rate), duration, isAbsolute, type, pumpId, pumpType, pumpSerial) }

    fun syncStopTemporaryBasalWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String, ignorePumpIds: Boolean = false) =
        runBlocking { delegate.syncStopTemporaryBasalWithPumpId(timestamp, endPumpId, pumpType, pumpSerial, ignorePumpIds) }

    fun insertTherapyEventIfNewWithTimestamp(timestamp: Long, type: TE.Type, note: String? = null, pumpId: Long? = null, pumpType: PumpType, pumpSerial: String) =
        runBlocking { delegate.insertTherapyEventIfNewWithTimestamp(timestamp, type, note, pumpId, pumpType, pumpSerial) }

    fun insertAnnouncement(error: String, pumpId: Long? = null, pumpType: PumpType, pumpSerial: String) =
        runBlocking { delegate.insertAnnouncement(error, pumpId, pumpType, pumpSerial) }

    fun createOrUpdateTotalDailyDose(timestamp: Long, bolusAmount: Double, basalAmount: Double, totalAmount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = runBlocking {
        // Unlike bolus/TBR methods this core method accepts IU, not a typed pump amount.
        val concentration = profileFunction.getProfile(timestamp)?.insulinConcentration() ?: return@runBlocking false
        delegate.createOrUpdateTotalDailyDose(timestamp, bolusAmount * concentration, basalAmount * concentration, totalAmount * concentration, pumpId, pumpType, pumpSerial)
    }
}
