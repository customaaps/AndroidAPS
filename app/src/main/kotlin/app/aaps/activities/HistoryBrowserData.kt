package app.aaps.activities

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.keys.Preferences
import app.aaps.plugins.main.general.overview.OverviewDataImpl
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryBrowserData @Inject constructor(
    aapsSchedulers: AapsSchedulers,
    rxBus: RxBus,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    dateUtil: DateUtil,
    sp: SP,
    preferences: Preferences,
    activePlugin: ActivePlugin,
    profileFunction: ProfileFunction,
    persistenceLayer: PersistenceLayer,
    fabricPrivacy: FabricPrivacy,
    calculationWorkflow: CalculationWorkflow,
    decimalFormatter: DecimalFormatter,
    processedTbrEbData: ProcessedTbrEbData,
    profileUtil: ProfileUtil,
    processedDeviceStatusData: ProcessedDeviceStatusData,
    config: Config,
    constraintsChecker: ConstraintsChecker
) {
    // We don't want to use injected singletons but own instance working on top of different data
    val overviewData =
        OverviewDataImpl(
            rh, dateUtil, sp, activePlugin, profileFunction, persistenceLayer, processedTbrEbData, preferences,
            profileUtil, processedDeviceStatusData, config, aapsLogger, constraintsChecker)
    val iobCobCalculator =
        IobCobCalculatorPlugin(
            aapsLogger, aapsSchedulers, rxBus, preferences, rh, profileFunction, activePlugin,
            fabricPrivacy, dateUtil, persistenceLayer, overviewData, calculationWorkflow, decimalFormatter, processedTbrEbData
        )
}