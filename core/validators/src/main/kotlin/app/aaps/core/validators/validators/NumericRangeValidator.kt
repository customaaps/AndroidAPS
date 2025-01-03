package app.aaps.core.validators.validators

import android.widget.EditText

/**
 * A validator that returns true only if the input field contains only numbers
 * and the number is within the given range.
 *
 * @author Said Tahsin Dane <tasomaniac></tasomaniac>@gmail.com>
 */
class NumericRangeValidator(customErrorMessage: String?, private val min: Int, private val max: Int) : Validator(customErrorMessage) {

    override fun isValid(editText: EditText): Boolean {
        return try {
            val value = editText.text.toString().toInt()
            value in min..max
        } catch (_: NumberFormatException) {
            false
        }
    }

}