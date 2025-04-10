package app.aaps.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.ui.databinding.WidgetConfigureBinding
import dagger.android.DaggerActivity
import javax.inject.Inject

/**
 * The configuration screen for the [Widget] AppWidget.
 */
class WidgetConfigureActivity : DaggerActivity() {

    @Inject lateinit var sp: SP

    companion object {

        const val PREF_PREFIX_KEY = "appwidget_"
        const val DEFAULT_OPACITY = 25

        const val STATUS_PREFIX_KEY = "status_"
        const val DEFAULT_STATUS_ENABLED = true
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    public override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // Set the result to CANCELED.  This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        val binding = WidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                saveOpacityPref(appWidgetId, progress)
                Widget.updateWidget(this@WidgetConfigureActivity, "WidgetConfigure")
            }
        })
        binding.statusSwitch.setOnCheckedChangeListener { _, v ->
            saveStatusPref(appWidgetId, v)
            Widget.updateWidget(this, "WidgetConfigure")
        }

        binding.closeLayout.close.setOnClickListener {
            // Make sure we pass back the original appWidgetId
            val resultValue = Intent()
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }

        binding.useBlack.setOnCheckedChangeListener { _, value ->
            sp.putBoolean(PREF_PREFIX_KEY + "use_black_$appWidgetId", value)
            Widget.updateWidget(this@WidgetConfigureActivity, "WidgetConfigure")
        }

        // Find the widget id from the intent.
        appWidgetId = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        binding.seekBar.progress = loadOpacityPref(appWidgetId)
        binding.statusSwitch.isChecked = loadStatusPref(appWidgetId)
        binding.useBlack.isChecked = sp.getBoolean(PREF_PREFIX_KEY + "use_black_$appWidgetId", false)
    }

    // Write the prefix to the SharedPreferences object for this widget
    fun saveOpacityPref(appWidgetId: Int, value: Int) {
        sp.putInt(PREF_PREFIX_KEY + appWidgetId, value)
    }

    fun saveStatusPref(appWidgetId: Int, value: Boolean) {
        sp.putBoolean(PREF_PREFIX_KEY + STATUS_PREFIX_KEY + appWidgetId, value)
    }

    private fun loadOpacityPref(appWidgetId: Int): Int = sp.getInt(PREF_PREFIX_KEY + appWidgetId, DEFAULT_OPACITY)
    private fun loadStatusPref(appWidgetId: Int): Boolean = sp.getBoolean(PREF_PREFIX_KEY + STATUS_PREFIX_KEY + appWidgetId, DEFAULT_STATUS_ENABLED)
}