package app.aaps.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.WarnColors
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.extensions.toVisibilityKeepSpace
import app.aaps.ui.R
import dagger.android.HasAndroidInjector
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Implementation of App Widget functionality.
 */
class Widget : AppWidgetProvider() {

    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var processedTbrEbData: ProcessedTbrEbData
    @Inject lateinit var loop: Loop
    @Inject lateinit var config: Config
    @Inject lateinit var sp: SP
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var preferences: Preferences

    var isMini = false

    companion object {

        // This object doesn't behave like singleton,
        // many threads were created. Making handler static resolve this issue
        private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

        fun updateWidget(context: Context, from: String) {
            context.sendBroadcast(Intent().also {
                it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, Widget::class.java)))
                it.putExtra("from", from)
                it.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            })
        }
    }

    private val intentAction = "OpenApp"

    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)
        aapsLogger.debug(LTag.WIDGET, "onReceive ${intent?.extras?.getString("from")}")
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val alpha = sp.getInt(WidgetConfigureActivity.PREF_PREFIX_KEY + appWidgetId, WidgetConfigureActivity.DEFAULT_OPACITY)
        val showStatus = sp.getBoolean(WidgetConfigureActivity.PREF_PREFIX_KEY + WidgetConfigureActivity.STATUS_PREFIX_KEY + appWidgetId, WidgetConfigureActivity.DEFAULT_STATUS_ENABLED)
        val useBlack = sp.getBoolean(WidgetConfigureActivity.PREF_PREFIX_KEY + "use_black_$appWidgetId", false)

        // Create an Intent to launch MainActivity when clicked
        val intent = Intent(context, uiInteraction.mainActivity).also { it.action = intentAction }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Widgets allow click handlers to only launch pending intents
        views.setOnClickPendingIntent(R.id.widget_layout, pendingIntent)
        if (config.APS || useBlack)
            views.setInt(R.id.widget_layout, "setBackgroundColor", Color.argb(alpha, 0, 0, 0))
        else if (config.AAPSCLIENT1)
            views.setInt(R.id.widget_layout, "setBackgroundColor", Color.argb(alpha, 0xE8, 0xC5, 0x0C))
        else if (config.AAPSCLIENT2)
            views.setInt(R.id.widget_layout, "setBackgroundColor", Color.argb(alpha, 0x0F, 0xBB, 0xE0))

        handler.post {
            if (config.appInitialized) {
                val variableSens =
                    if (config.APS) loop.lastRun?.request?.variableSens
                    else if (config.AAPSCLIENT) processedDeviceStatusData.getAPSResult()?.variableSens
                    else null

                // status line doesn't have space only if 2 lines are used (AS + VS)
                isMini = showStatus && variableSens != null && constraintChecker.isAutosensModeEnabled().value()
                updateBg(views)
                updateTemporaryBasal(views)
                updateExtendedBolus(views)
                updateIobCob(views)
                updateTemporaryTarget(views)
                updateProfile(views)
                updateSensitivity(views)

                for (i in arrayOf(R.id.iob_icon, R.id.carbs_icon, R.id.extended_icon, R.id.sensitivity_icon, R.id.base_basal_icon))
                    views.setViewVisibility(i, if (isMini) View.GONE else View.VISIBLE)

                for (i in arrayOf(R.id.iob_icon_mini, R.id.carbs_icon_mini, R.id.extended_icon_mini, R.id.sensitivity_icon_mini, R.id.base_basal_icon_mini))
                    views.setViewVisibility(i, if (isMini) View.VISIBLE else View.GONE)

                if (showStatus) {
                    views.setViewPadding(R.id.status_layout, 4, if (isMini) 0 else 6, 4, 0)

                    val pump = activePlugin.activePump
                    updateAge(views, R.id.cannula_age, TE.Type.CANNULA_CHANGE, IntKey.OverviewCageWarning, IntKey.OverviewCageCritical)
                    updateAge(views, R.id.sensor_age, TE.Type.SENSOR_CHANGE, IntKey.OverviewSageWarning, IntKey.OverviewSageCritical)
                    updateAge(views, R.id.insulin_age, TE.Type.INSULIN_CHANGE, IntKey.OverviewIageWarning, IntKey.OverviewIageCritical)

                    val insulinUnit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
                    val maxReading = pump.pumpDescription.maxResorvoirReading
                    views.setTextColor(R.id.reservoir_level, rh.gc(when {
                        pump.reservoirLevel <= preferences.get(IntKey.OverviewResCritical) -> app.aaps.core.ui.R.color.widget_ribbonCritical
                        pump.reservoirLevel <= preferences.get(IntKey.OverviewResWarning) -> app.aaps.core.ui.R.color.widget_ribbonWarning
                        else -> app.aaps.core.ui.R.color.widget_ribbonTextDefault
                    }))
                    val reservoirString = if (!pump.pumpDescription.isPatchPump)
                        pump.reservoirLevel.roundToInt().toString()
                    else
                       if (pump.reservoirLevel >= maxReading) "$maxReading+" else pump.reservoirLevel.roundToInt().toString()
                    views.setTextViewText(R.id.reservoir_level, reservoirString + insulinUnit)

                    views.setViewVisibility(R.id.pb_level, View.GONE)
                    views.setViewVisibility(R.id.pb_age, View.GONE)
                    if (pump.isBatteryChangeLoggingEnabled() || pump.pumpDescription.isBatteryReplaceable) {
                        views.setViewVisibility(R.id.pb_age, View.VISIBLE)
                        updateAge(views, R.id.pb_age, TE.Type.PUMP_BATTERY_CHANGE, IntKey.OverviewBageWarning, IntKey.OverviewBageCritical)
                    }

                    if (!config.AAPSCLIENT) {
                        // The Omnipod Eros does not report its battery level. However, some RileyLink alternatives do.
                        // Depending on the user's configuration, we will either show the battery level reported by the RileyLink or "n/a"
                        // Pump instance check is needed because at startup, the pump can still be VirtualPumpPlugin and that will cause a crash
                        val erosBatteryLinkAvailable = pump.model() == PumpType.OMNIPOD_EROS && pump.isUseRileyLinkBatteryLevel()

                        if (pump.model().supportBatteryLevel || erosBatteryLinkAvailable) {
                            views.setViewVisibility(R.id.pb_level, View.VISIBLE)
                            views.setTextColor(R.id.pb_level, rh.gc(when {
                                pump.batteryLevel <= preferences.get(IntKey.OverviewBattCritical) -> app.aaps.core.ui.R.color.widget_ribbonCritical
                                pump.batteryLevel <= preferences.get(IntKey.OverviewBattWarning) -> app.aaps.core.ui.R.color.widget_ribbonWarning
                                else -> app.aaps.core.ui.R.color.widget_ribbonTextDefault
                            }))
                            views.setTextViewText(R.id.pb_level, pump.batteryLevel.toString() + "%")
                        }
                    }
                }
                views.setViewVisibility(R.id.status_layout, if (!showStatus) View.GONE else View.VISIBLE)

                // Instruct the widget manager to update the widget
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun updateAge(views: RemoteViews, viewId: Int, type: TE.Type, warnSettings: IntKey, urgentSettings: IntKey) {
        val warn = preferences.get(warnSettings)
        val urgent = preferences.get(urgentSettings)
        val therapyEvent = persistenceLayer.getLastTherapyRecordUpToNow(type)
        if (therapyEvent != null) {
            views.setTextViewText(viewId, therapyEvent.age())
            if (preferences.get(BooleanKey.OverviewShowStatusLights))
                views.setTextColor(viewId, rh.gc(therapyEvent.color(warn, urgent)))
        } else {
            views.setTextViewText(viewId, "-")
        }
    }

    private fun updateBg(views: RemoteViews) {
        views.setTextViewText(
            R.id.bg,
            lastBgData.lastBg()?.let { profileUtil.fromMgdlToStringInUnits(it.recalculated) } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
        views.setTextColor(
            R.id.bg, when {
                lastBgData.isLow()  -> rh.gc(app.aaps.core.ui.R.color.widget_low)
                lastBgData.isHigh() -> rh.gc(app.aaps.core.ui.R.color.widget_high)
                else                -> rh.gc(app.aaps.core.ui.R.color.widget_inrange)
            }
        )
        trendCalculator.getTrendArrow(iobCobCalculator.ads)?.let {
            views.setImageViewResource(R.id.arrow, it.directionToIcon())
        }
        views.setViewVisibility(R.id.arrow, (trendCalculator.getTrendArrow(iobCobCalculator.ads) != null).toVisibilityKeepSpace())
        views.setInt(
            R.id.arrow, "setColorFilter", when {
                lastBgData.isLow()  -> rh.gc(app.aaps.core.ui.R.color.widget_low)
                lastBgData.isHigh() -> rh.gc(app.aaps.core.ui.R.color.widget_high)
                else                -> rh.gc(app.aaps.core.ui.R.color.widget_inrange)
            }
        )

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if (glucoseStatus != null) {
            views.setTextViewText(R.id.delta, profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta))
            views.setTextViewText(R.id.avg_delta, profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.shortAvgDelta))
            views.setTextViewText(R.id.long_avg_delta, profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.longAvgDelta))
        } else {
            views.setTextViewText(R.id.delta, rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
            views.setTextViewText(R.id.avg_delta, rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
            views.setTextViewText(R.id.long_avg_delta, rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
        }

        // strike through if BG is old
        if (!lastBgData.isActualBg()) views.setInt(R.id.bg, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
        else views.setInt(R.id.bg, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)

        views.setTextViewText(R.id.time_ago, dateUtil.minAgo(rh, lastBgData.lastBg()?.timestamp))
        //views.setTextViewText(R.id.time_ago_short, "(" + dateUtil.minAgoShort(overviewData.lastBg?.timestamp) + ")")
    }

    private fun updateTemporaryBasal(views: RemoteViews) {
        views.setTextViewText(R.id.base_basal, overviewData.temporaryBasalText())
        views.setTextColor(R.id.base_basal, processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let { rh.gc(app.aaps.core.ui.R.color.widget_basal) }
            ?: rh.gc(app.aaps.core.ui.R.color.white))
        views.setImageViewResource(if (isMini) R.id.base_basal_icon_mini else R.id.base_basal_icon, overviewData.temporaryBasalIcon())
    }

    private fun updateExtendedBolus(views: RemoteViews) {
        val pump = activePlugin.activePump
        views.setTextViewText(R.id.extended_bolus, overviewData.extendedBolusText())
        views.setViewVisibility(R.id.extended_layout, (persistenceLayer.getExtendedBolusActiveAt(dateUtil.now()) != null && !pump.isFakingTempsByExtendedBoluses).toVisibility())
    }

    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()
    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
    private fun iobText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob)

    private fun updateIobCob(views: RemoteViews) {
        views.setTextViewText(R.id.iob, iobText())
        // cob
        var cobText = iobCobCalculator.getCobInfo("Overview COB").displayText(rh, decimalFormatter) ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                //only display carbsreq when carbs have not been entered recently
                val lastCarbsTime = persistenceLayer.getNewestCarbs()?.timestamp ?: 0L
                if (lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " | " + constraintsProcessed.carbsReq + " " + rh.gs(app.aaps.core.ui.R.string.required)
                }
            }
        }
        views.setTextViewText(R.id.cob, cobText)
    }

    private fun updateTemporaryTarget(views: RemoteViews) {
        val units = profileFunction.getUnits()
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
        if (tempTarget != null) {
            // this is crashing, use background as text for now
            //views.setTextColor(R.id.temp_target, rh.gc(R.color.ribbonTextWarning))
            //views.setInt(R.id.temp_target, "setBackgroundColor", rh.gc(R.color.ribbonWarning))
            views.setTextColor(R.id.temp_target, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
            views.setTextViewText(
                R.id.temp_target,
                profileUtil.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh)
            )
        } else {
            // If the target is not the same as set in the profile then oref has overridden it
            profileFunction.getProfile()?.let { profile ->
                val targetUsed = loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0

                if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                    aapsLogger.debug("Adjusted target. Profile: ${profile.getTargetMgdl()} APS: $targetUsed")
                    views.setTextViewText(R.id.temp_target, profileUtil.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units))
                    // this is crashing, use background as text for now
                    //views.setTextColor(R.id.temp_target, rh.gc(R.color.ribbonTextWarning))
                    //views.setInt(R.id.temp_target, "setBackgroundResource", rh.gc(R.color.tempTargetBackground))
                    views.setTextColor(R.id.temp_target, rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning))
                } else {
                    // this is crashing, use background as text for now
                    //views.setTextColor(R.id.temp_target, rh.gc(R.color.ribbonTextDefault))
                    //views.setInt(R.id.temp_target, "setBackgroundColor", rh.gc(R.color.ribbonDefault))
                    views.setTextColor(R.id.temp_target, rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault))
                    views.setTextViewText(R.id.temp_target, profileUtil.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units))
                }
            }
        }
    }

    private fun updateProfile(views: RemoteViews) {
        val profileTextColor =
            profileFunction.getProfile()?.let {
                if (it is ProfileSealed.EPS) {
                    if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                        rh.gc(app.aaps.core.ui.R.color.widget_ribbonWarning)
                    else rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault)
                } else if (it is ProfileSealed.PS) {
                    rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault)
                } else {
                    rh.gc(app.aaps.core.ui.R.color.widget_ribbonTextDefault)
                }
            } ?: rh.gc(app.aaps.core.ui.R.color.widget_ribbonCritical)

        views.setTextViewText(R.id.active_profile, profileFunction.getProfileNameWithRemainingTime())
        // this is crashing, use background as text for now
        //views.setInt(R.id.active_profile, "setBackgroundColor", profileBackgroundColor)
        //views.setTextColor(R.id.active_profile, profileTextColor)
        views.setTextColor(R.id.active_profile, profileTextColor)
    }

    private fun updateSensitivity(views: RemoteViews) {
        views.setImageViewResource(
            if (isMini) R.id.sensitivity_icon_mini else R.id.sensitivity_icon,
           overviewData.autoOrTddSensRatio(loop, iobCobCalculator)?.let {
                when {
                    it > 1.0 -> app.aaps.core.objects.R.drawable.ic_as_above
                    it < 1.0 -> app.aaps.core.objects.R.drawable.ic_as_below
                    else     -> app.aaps.core.objects.R.drawable.ic_swap_vert_black_48dp_green
                }
            } ?: app.aaps.core.objects.R.drawable.ic_x_swap_vert
        )

        val text = overviewData.sensitivityText(false, loop, iobCobCalculator)
        views.setTextViewText(R.id.sensitivity, text)
        views.setViewVisibility(R.id.sensitivity, if (text.isEmpty()) View.GONE else View.VISIBLE)
    }

    private fun TE.age(): String {
        val diff = dateUtil.computeDiff(timestamp, System.currentTimeMillis())
        val days = rh.gs(app.aaps.core.interfaces.R.string.shortday)
        val hours = rh.gs(app.aaps.core.interfaces.R.string.shorthour)
        return diff[TimeUnit.DAYS].toString() + days + diff[TimeUnit.HOURS].toString() + hours
    }

    private fun TE.color(warnLevel: Int, urgentLevel: Int): Int {
        val value = dateUtil.computeDiff(timestamp, System.currentTimeMillis())[TimeUnit.HOURS]?.toInt() ?: return app.aaps.core.ui.R.color.widget_ribbonTextDefault
        return when {
            value >= urgentLevel -> app.aaps.core.ui.R.color.widget_ribbonCritical
            value >= warnLevel   -> app.aaps.core.ui.R.color.widget_ribbonWarning
            else                 -> app.aaps.core.ui.R.color.widget_ribbonTextDefault
        }
    }
}
