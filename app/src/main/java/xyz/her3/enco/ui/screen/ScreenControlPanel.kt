package xyz.her3.enco.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.her3.enco.R
import xyz.her3.enco.device.shell.ScreenFrame
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors
import kotlin.math.roundToInt

@Composable
internal fun ScreenControlPanel(
    frame: ScreenFrame?,
    busy: Boolean,
    errorMessage: Int?,
    onCapture: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onTouch: (Long, Int, Int, Int, Int, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val panelShape = RoundedCornerShape(22.dp)
    Column(
        modifier =
            Modifier
                .padding(8.dp)
                .widthIn(max = 232.dp)
                .shadow(6.dp, panelShape)
                .background(colors.panel, panelShape)
                .border(1.dp, colors.line, panelShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(colors.accentTint, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ControlIcon(ControlSymbol.PHONE, Modifier.size(17.dp), colors.accent)
            }
            EncoText(
                stringResource(R.string.screen_control_title),
                modifier = Modifier.weight(1f),
                weight = FontWeight.SemiBold,
            )
            PanelAction(ControlSymbol.CLOSE, R.string.screen_control_close, true, onClose)
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(colors.inset, RoundedCornerShape(14.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (frame != null) ScreenPreview(frame, onTouch)
            EncoText(
                stringResource(errorMessage ?: if (frame != null) R.string.screen_control_hint else R.string.screen_control_empty),
                size = 12.sp,
                color = if (errorMessage == null) colors.muted else colors.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            PanelAction(ControlSymbol.BACK, R.string.screen_control_back, !busy, onBack)
            PanelAction(ControlSymbol.CAPTURE, R.string.screen_control_capture, !busy, onCapture, primary = true)
            PanelAction(ControlSymbol.HOME, R.string.screen_control_home, !busy, onHome)
        }
    }
}

@Composable
private fun PanelAction(
    symbol: ControlSymbol,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val colors = LocalEncoColors.current
    val description = stringResource(label)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (primary && enabled) colors.accent else Color.Transparent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (primary && enabled) colors.onAccent else colors.muted
        ControlIcon(symbol, Modifier.size(20.dp), tint)
    }
}

@Composable
private fun ScreenPreview(
    frame: ScreenFrame,
    onTouch: (Long, Int, Int, Int, Int, Boolean) -> Unit,
) {
    val image = frame.image ?: return
    val ratio = image.width.toFloat() / image.height
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.screen_control_preview),
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .width(minOf(maxWidth, 296.dp * ratio))
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(frame.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var dragged = false
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.singleOrNull { it.id == down.id } ?: break
                                if (pointer.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                if ((pointer.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                                    dragged = true
                                }
                                pointer.consume()
                                if (!pointer.pressed) {
                                    onTouch(
                                        frame.id,
                                        normalize(down.position.x, size.width),
                                        normalize(down.position.y, size.height),
                                        normalize(pointer.position.x, size.width),
                                        normalize(pointer.position.y, size.height),
                                        dragged,
                                    )
                                    break
                                }
                            } while (true)
                        }
                    },
        )
    }
}

private fun normalize(
    position: Float,
    length: Int,
): Int = (position * 1000 / length).roundToInt().coerceIn(0, 1000)
