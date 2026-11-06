package app.aaps.ui.compose.aboutDialog

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.interfaces.configuration.ExternalOptions

@Preview(showBackground = true)
@Composable
internal fun AboutAlertDialogPreview() {
    MaterialTheme {
        AboutAlertDialog(
            data = AboutDialogData(
                title = "AAPS 4.0.0",
                message = "Build: 4.0.0\nFlavor: full\n\nhttps://androidaps.org",
                enabledOptions = listOf(ExternalOptions.ENGINEERING_MODE, ExternalOptions.UNFINISHED_MODE)
            ),
            onDismiss = {},
            onOpenBatteryHelp = {}
        )
    }
}
