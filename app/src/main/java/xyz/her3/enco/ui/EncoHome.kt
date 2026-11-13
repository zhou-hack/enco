package xyz.her3.enco.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.her3.enco.R
import xyz.her3.enco.device.shizuku.ShizukuStatus
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors

@Composable
internal fun EncoHome(
    status: ShizukuStatus,
    overlayAllowed: Boolean,
    onOpenShizuku: () -> Unit,
    onRetry: () -> Unit,
    onOverlaySettings: () -> Unit,
    onOpenScreenControl: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val connected = status == ShizukuStatus.CONNECTED
    val ready = connected && overlayAllowed
    val groupShape = RoundedCornerShape(22.dp)

    Box(
        modifier =
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colorStops = arrayOf(0f to colors.backdrop, .64f to colors.background)),
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EncoText(stringResource(R.string.app_name), size = 19.sp, weight = FontWeight.Bold)
                StatusLabel(
                    if (ready) R.string.screen_control_ready else R.string.screen_control_setup_needed,
                    ready,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EncoText(stringResource(R.string.screen_control_title), size = 30.sp, weight = FontWeight.Bold)
                EncoText(stringResource(R.string.screen_control_description), color = colors.muted)
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(groupShape)
                        .background(colors.panel)
                        .border(1.dp, colors.line, groupShape)
                        .clickable(enabled = ready, role = Role.Button, onClick = onOpenScreenControl)
                        .heightIn(min = 76.dp)
                        .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(44.dp).background(colors.accentTint, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    ControlIcon(ControlSymbol.PHONE, color = colors.accent)
                }
                EncoText(
                    stringResource(R.string.screen_control_open),
                    modifier = Modifier.weight(1f),
                    color = if (ready) colors.text else colors.muted,
                    weight = FontWeight.SemiBold,
                )
                ControlIcon(ControlSymbol.NEXT, Modifier.size(18.dp), if (ready) colors.accent else colors.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                EncoText(
                    stringResource(R.string.screen_control_setup),
                    modifier = Modifier.padding(start = 4.dp),
                    size = 13.sp,
                    color = colors.muted,
                    weight = FontWeight.SemiBold,
                )
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.panel, groupShape)
                            .border(1.dp, colors.line, groupShape)
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    val (actionLabel, action) =
                        when (status) {
                            ShizukuStatus.STOPPED, ShizukuStatus.UPDATE_REQUIRED, ShizukuStatus.MANAGE_PERMISSION -> {
                                R.string.shizuku_open to onOpenShizuku
                            }

                            ShizukuStatus.NEED_PERMISSION -> {
                                R.string.shizuku_grant to onRetry
                            }

                            ShizukuStatus.CONNECTION_FAILED -> {
                                R.string.shizuku_retry to onRetry
                            }

                            else -> {
                                null to null
                            }
                        }
                    PermissionRow(ControlSymbol.LINK, R.string.shizuku_title, status.message, connected, actionLabel, action)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                    PermissionRow(
                        ControlSymbol.LAYERS,
                        R.string.screen_control_overlay,
                        if (overlayAllowed) R.string.screen_control_overlay_ready else R.string.screen_control_permission,
                        overlayAllowed,
                        if (overlayAllowed) null else R.string.screen_control_overlay_grant,
                        if (overlayAllowed) null else onOverlaySettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(
    label: Int,
    ready: Boolean,
) {
    val colors = LocalEncoColors.current
    val color = if (ready) colors.positive else colors.muted
    Row(
        modifier =
            Modifier
                .background(if (ready) colors.positiveTint else colors.panel, CircleShape)
                .padding(horizontal = 11.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        EncoText(stringResource(label), size = 12.sp, color = color, weight = FontWeight.Medium)
    }
}

@Composable
private fun PermissionRow(
    symbol: ControlSymbol,
    title: Int,
    detail: Int,
    ready: Boolean,
    actionLabel: Int?,
    action: (() -> Unit)?,
) {
    val colors = LocalEncoColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .background(if (ready) colors.positiveTint else colors.inset, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ControlIcon(symbol, Modifier.size(18.dp), if (ready) colors.positive else colors.muted)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            EncoText(stringResource(title), weight = FontWeight.SemiBold)
            EncoText(stringResource(detail), size = 12.sp, color = colors.muted)
            if (actionLabel != null && action != null) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClick = action)
                            .heightIn(min = 48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    EncoText(stringResource(actionLabel), color = colors.accent, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val ShizukuStatus.message: Int
    get() =
        when (this) {
            ShizukuStatus.CHECKING -> R.string.shizuku_checking
            ShizukuStatus.STOPPED -> R.string.shizuku_stopped
            ShizukuStatus.UPDATE_REQUIRED -> R.string.shizuku_update_required
            ShizukuStatus.NEED_PERMISSION -> R.string.shizuku_needs_permission
            ShizukuStatus.REQUESTING_PERMISSION -> R.string.shizuku_requesting_permission
            ShizukuStatus.MANAGE_PERMISSION -> R.string.shizuku_manage_permission
            ShizukuStatus.CONNECTING -> R.string.shizuku_connecting
            ShizukuStatus.CONNECTED -> R.string.shizuku_connected
            ShizukuStatus.CONNECTION_FAILED -> R.string.shizuku_connection_failed
        }
