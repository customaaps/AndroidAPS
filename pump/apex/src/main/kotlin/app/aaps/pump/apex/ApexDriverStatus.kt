package app.aaps.pump.apex

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class ApexDriverStatus(
    val rh: ResourceHelper,
    val rxBus: RxBus,
    val config: Config,
) {
    /// There may be multiple actions ongoing, but we need to show
    /// only the most priority one.
    private val actions = mutableMapOf<Action, String>()

    /// If connection state is not Connected, then we need to show
    /// connection state instead of actions.
    private var connectionState = ConnectionState.Disconnected

    /// Last status message
    private var lastMessage: String? = null

    /// Possible driver actions list. In order of descending priority.
    enum class Action {
        DriverError,

        CancelingBolus,
        CancelingTBR,
        SettingBolus,
        SettingMicroBolus,
        SettingTBR,

        Bolusing,
        MicroBolusing,

        RequestingHeartbeat,
        UpdatingDateTime,
        SettingBasalProfileContents,
        SettingBasalProfileIndex,
        UpdatingSettings,
        UpdatingSystemState,

        GettingVersion,
        GettingTDDs,
        GettingBoluses,
        GettingAlarms,
        GettingRefills,
        GettingStatus,
        GettingBasalProfiles,

        FallbackExecutingCommand,
        FallbackGettingValue,

        Initializing,
        Reconnecting,
        CheckingPump,
        Heartbeat,
    }

    enum class ConnectionState {
        Disconnecting,
        Disconnected,
        Connecting,
        Connected;

        val asPumpStatus get() = when (this) {
            Disconnecting -> EventPumpStatusChanged.Status.DISCONNECTING
            Disconnected -> EventPumpStatusChanged.Status.DISCONNECTED
            Connecting -> EventPumpStatusChanged.Status.CONNECTING
            Connected -> EventPumpStatusChanged.Status.CONNECTED
        }
    }

    private fun notifyListeners() {
        val mostImportant = actions.entries.minByOrNull { it.key.ordinal }
        lastMessage = if (config.isEngineeringMode())
            actions.entries.sortedBy { it.key.ordinal }.joinToString("\n") { "· ${it.value}" }
        else
            mostImportant?.value

        // Sending EventPumpStatusChanged while bolusing closes the bolus dialog.
        if (connectionState != ConnectionState.Connected || actions.isEmpty())
            rxBus.send(EventPumpStatusChanged(connectionState.asPumpStatus))
        else
            rxBus.send(EventPumpStatusChanged(
                when (connectionState) {
                    ConnectionState.Disconnected -> rh.gs(app.aaps.core.ui.R.string.disconnected)
                    ConnectionState.Disconnecting -> rh.gs(app.aaps.core.interfaces.R.string.disconnecting)
                    ConnectionState.Connecting -> rh.gs(app.aaps.core.ui.R.string.connecting)
                    else -> lastMessage?.split("\n")?.firstOrNull() ?: rh.gs(app.aaps.core.interfaces.R.string.connected)
                }
            ))
        rxBus.send(EventApexPumpDataChanged())
    }

    val message get() = lastMessage

    fun addAction(action: Action, message: String) {
        if (actions.containsKey(action)) return
        updateOrAddAction(action, message)
    }

    fun updateAction(action: Action, message: String) {
        if (!actions.containsKey(action)) return
        updateOrAddAction(action, message)
    }

    fun addAction(action: Action, message: Int) =
        addAction(action, rh.gs(message))

    fun updateAction(action: Action, message: Int) =
        updateAction(action, rh.gs(message))

    fun updateOrAddAction(action: Action, message: Int) =
        updateOrAddAction(action, rh.gs(message))

    // The three mutators below all notify, and notifying costs two RxBus broadcasts plus a string
    // build. They were called on every heartbeat cycle whether or not anything had moved - the
    // connection state in particular is re-asserted as Connected about every two minutes - and the
    // driver ended up producing roughly 13000 broadcasts in a 9 hour capture, close to 60 % of all
    // RxBus traffic in the app. Each of them now returns when the new value equals the current one,
    // so reaching notifyListeners() means something really changed.

    fun updateOrAddAction(action: Action, message: String) = synchronized(actions) {
        if (actions[action] == message) return@synchronized
        actions[action] = message
        notifyListeners()
    }

    fun removeAction(action: Action) = synchronized(actions)  {
        if (actions.remove(action) == null) return@synchronized
        notifyListeners()
    }

    fun updateConnectionState(state: ConnectionState) = synchronized(actions) {
        if (connectionState == state) return@synchronized
        connectionState = state
        notifyListeners()
    }
}