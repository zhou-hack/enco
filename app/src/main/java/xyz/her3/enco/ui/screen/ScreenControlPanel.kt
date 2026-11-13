package xyz.her3.enco.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.her3.enco.R
import xyz.her3.enco.device.shell.ScreenFrame
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
    Surface(
        modifier = Modifier.padding(8.dp).widthIn(max = 280.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (errorMessage != null) {
                Text(stringResource(errorMessage), color = MaterialTheme.colorScheme.error)
            }
            if (frame != null) {
                ScreenPreview(frame, onTouch)
                Text(stringResource(R.string.screen_control_hint), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onCapture, enabled = !busy) { Text(stringResource(R.string.screen_control_capture)) }
                TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.screen_control_back)) }
                TextButton(onClick = onHome, enabled = !busy) { Text(stringResource(R.string.screen_control_home)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.screen_control_close)) }
            }
        }
    }
}

@Composable
private fun ScreenPreview(
    frame: ScreenFrame,
    onTouch: (Long, Int, Int, Int, Int, Boolean) -> Unit,
) {
    val image = frame.image ?: return
    val ratio = image.width.toFloat() / image.height
    BoxWithConstraints(modifier = Modifier.width(248.dp)) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.screen_control_preview),
            contentScale = ContentScale.FillBounds,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .width(minOf(maxWidth, 264.dp * ratio))
                    .aspectRatio(ratio)
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
