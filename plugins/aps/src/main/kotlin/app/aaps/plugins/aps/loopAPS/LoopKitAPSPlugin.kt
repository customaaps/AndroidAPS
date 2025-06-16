import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.Preferences
import app.aaps.plugins.aps.GenericAPSPluginBase

import app.aaps.plugins.aps.R
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class LoopKitAPSPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    config: Config,
    persistenceLayer: PersistenceLayer,
    dateUtil: DateUtil,
    preferences: Preferences,
    uiInteraction: UiInteraction,
    profiler: Profiler,
    activePlugin: ActivePlugin,
    glucoseStatusProvider: GlucoseStatusProvider,
    hardLimits: HardLimits,
    rxBus: RxBus,
    profileFunction: ProfileFunction,
    constraintsChecker: ConstraintsChecker,
    iobCobCalculator: IobCobCalculator,
    processedTbrEbData: ProcessedTbrEbData,
    val objectives: Objectives,
): GenericAPSPluginBase(
    aapsLogger, rh, config, persistenceLayer, dateUtil, preferences,
    uiInteraction, profiler, activePlugin, glucoseStatusProvider, hardLimits,
    rxBus, profileFunction, constraintsChecker, iobCobCalculator, processedTbrEbData
) {

    override val algorithm = APSResult.Algorithm.LOOPKIT

    override fun getAutosensData(variableISFResult: VariableISFResult?): AutosensResult? {
        // LoopKit doesn't have anything like autosens. Maybe it's possible to integrate it on top of LoopKit later.
        return AutosensResult()
    }

    override fun configuration() = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) {}

    override fun invokeLazy(data: InvokeData) {
        TODO("Not yet implemented")
    }

    // This implementation won't support open loop.
    override fun specialShowInListCondition(): Boolean {
        return super.specialShowInListCondition() && objectives.isAccomplished(Objectives.SMB_OBJECTIVE)
    }

    init {
        pluginDescription
            .pluginName(R.string.loopaps_name)
            .shortName(R.string.loopaps_name)
            .description(R.string.loopaps_desc)
            .showInList { config.APS && config.ADVANCED }
    }
}