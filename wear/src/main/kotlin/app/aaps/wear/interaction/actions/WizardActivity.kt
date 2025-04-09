@file:Suppress("DEPRECATION")

package app.aaps.wear.interaction.actions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData.ActionWizardPreCheck
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.interfaces.utils.SafeParse.stringToDouble
import app.aaps.core.keys.IntKey
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.EditPlusMinusViewAdapter
import app.aaps.wear.interaction.utils.PlusMinusEditText
import app.aaps.wear.nondeprecated.GridPagerAdapterNonDeprecated
import java.text.DecimalFormat

class WizardActivity : ViewSelectorActivity() {

    var editCarbs: PlusMinusEditText? = null
    var editPercentage: PlusMinusEditText? = null
    var editStartTime: PlusMinusEditText? = null
    var hasPercentage = false
    var hasDelay = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAdapter(MyGridViewPagerAdapter())
        hasPercentage = sp.getBoolean(R.string.key_wizard_percentage, false)
        hasDelay = sp.getBoolean(R.string.key_wizard_delay, false)
    }

    override fun onPause() {
        super.onPause()
        finish()
    }

    private inner class MyGridViewPagerAdapter : GridPagerAdapterNonDeprecated() {

        override fun getColumnCount(arg0: Int): Int {
            var base = 2
            if (hasPercentage) base++
            if (hasDelay) base++
            return base
        }
        override fun getRowCount(): Int = 1
        private val increment1 = preferences.get(IntKey.OverviewCarbsButtonIncrement1).toDouble()
        private val increment2 = preferences.get(IntKey.OverviewCarbsButtonIncrement2).toDouble()
        val stepValues = listOf(1.0, increment1, increment2)

        override fun instantiateItem(container: ViewGroup, row: Int, col: Int): View = when {
            col == 0                  -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, true)
                val view = viewAdapter.root
                val maxCarbs = sp.getInt(getString(R.string.key_treatments_safety_max_carbs), 48).toDouble()
                val initValue = stringToDouble(editCarbs?.editText?.text.toString(), 0.0)
                editCarbs = PlusMinusEditText(viewAdapter, initValue, 0.0, maxCarbs, stepValues, DecimalFormat("0"), false, getString(R.string.action_carbs))
                container.addView(view)
                view.requestFocus()
                view
            }

            col == 1 && hasPercentage -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, false)
                val view = viewAdapter.root
                val percentage = sp.getInt(getString(R.string.key_bolus_wizard_percentage), 100).toDouble()
                val initValue = stringToDouble(editPercentage?.editText?.text.toString(), percentage)
                editPercentage = PlusMinusEditText(viewAdapter, initValue, 10.0, 200.0, 5.0, DecimalFormat("0"), false, getString(R.string.action_percentage))
                container.addView(view)
                view
            }

            col == 2 && hasDelay -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container, false)
                val view = viewAdapter.root
                val initValue = stringToDouble(editStartTime?.editText?.text.toString(), 0.0)
                editStartTime = PlusMinusEditText(viewAdapter, initValue, -60.0, 300.0, stepValues, DecimalFormat("0"), false, getString(R.string.action_start_minutes))
                container.addView(view)
                view
            }

            else                      -> {
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_ok, container, false)
                view.findViewById<ImageView>(R.id.confirmbutton)
                    .setOnClickListener {
                        val percentage = if (hasPercentage) SafeParse.stringToInt(editPercentage?.editText?.text.toString()) else sp.getInt(getString(R.string.key_bolus_wizard_percentage), 100)
                        val startTime = if (hasDelay) SafeParse.stringToInt(editStartTime?.editText?.text.toString()) else sp.getInt(getString(R.string.key_bolus_wizard_delay), 0)
                        rxBus.send(EventWearToMobile(ActionWizardPreCheck(SafeParse.stringToInt(editCarbs?.editText?.text.toString()), percentage, startTime)))
                        showToast(this@WizardActivity, R.string.action_wizard_confirmation)
                        finishAffinity()
                    }
                container.addView(view)
                view
            }
        }

        override fun destroyItem(container: ViewGroup, row: Int, col: Int, view: Any) {
            // Handle this to get the data before the view is destroyed?
            // Object should still be kept by this, just setup for re-init?
            container.removeView(view as View)
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`
    }
}
