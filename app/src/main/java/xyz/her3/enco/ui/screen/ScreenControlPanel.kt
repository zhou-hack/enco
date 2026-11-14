package xyz.her3.enco.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.her3.enco.R
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors

internal enum class ScreenControlResultOption(
    val labelRes: Int,
) {
    COPY_TEXT(R.string.screen_control_option_copy_text),
    SAVE_SCHEDULE_DRAFT(R.string.screen_control_option_save_schedule),
    RECAPTURE(R.string.screen_control_option_recapture),
    CLOSE_RESULT(R.string.screen_control_option_close_result),
}

@Composable
internal fun ScreenControlPanel(
    busy: Boolean,
    onCapture: () -> Unit,
    resultOptions: List<ScreenControlResultOption>,
    onResultOptionSelected: (ScreenControlResultOption) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CaptureButton(enabled = !busy, onClick = onCapture)
        if (resultOptions.isNotEmpty()) {
            CompactResultOptions(
                options = resultOptions.take(4),
                onSelected = onResultOptionSelected,
            )
        }
    }
}

@Composable
private fun CaptureButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val description = stringResource(R.string.screen_control_capture)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) colors.accent else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ControlIcon(ControlSymbol.CAPTURE, Modifier.size(20.dp), if (enabled) colors.onAccent else colors.muted)
    }
}

@Composable
private fun CompactResultOptions(
    options: List<ScreenControlResultOption>,
    onSelected: (ScreenControlResultOption) -> Unit,
) {
    val colors = LocalEncoColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(top = 6.dp)
            .widthIn(max = 180.dp)
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.line, shape),
    ) {
        options.forEach { option ->
            EncoText(
                stringResource(option.labelRes),
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelected(option) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                size = 12.sp,
                color = colors.text,
                maxLines = 1,
            )
        }
    }
}
