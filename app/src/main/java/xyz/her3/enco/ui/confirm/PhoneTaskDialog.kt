package xyz.her3.enco.ui.confirm

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.VisionAction
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors

@Composable
internal fun PhoneTaskDialog(
    title: String,
    body: String,
    confirmLabel: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    preview: Bitmap? = null,
    tap: VisionAction.Tap? = null,
) {
    val colors = LocalEncoColors.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(22.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EncoText(title, size = 19.sp, weight = FontWeight.Bold)
            EncoText(body, color = colors.muted)
            if (preview != null && tap != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 145.dp)
                        .fillMaxWidth()
                        .aspectRatio(preview.width.toFloat() / preview.height),
                ) {
                    Image(preview.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.matchParentSize()) {
                        val point = Offset(size.width * tap.x / 1000, size.height * tap.y / 1000)
                        drawCircle(colors.error, radius = 9.dp.toPx(), center = point, style = Stroke(3.dp.toPx()))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogAction(R.string.phone_task_deny, false, onCancel)
                DialogAction(confirmLabel, true, onConfirm)
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: Int,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalEncoColors.current
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (primary) colors.accent else colors.inset)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        EncoText(stringResource(label), color = if (primary) colors.onAccent else colors.text, weight = FontWeight.SemiBold)
    }
}
