package xyz.her3.enco.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.her3.enco.R
import xyz.her3.enco.device.service.PhoneTaskStatus
import xyz.her3.enco.ui.ControlIcon
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.statusText
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors

@Composable
internal fun ChatScreen(
    state: ChatState,
    onEditDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModelSettings: () -> Unit,
    onPhoneControl: () -> Unit,
    task: PhoneTaskStatus,
    onStopPhoneTask: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val list = rememberLazyListState()
    val showPending = state.pending.isNotEmpty()
    LaunchedEffect(state.messages.size, state.pending) {
        if (state.messages.isNotEmpty() || showPending) list.scrollToItem(0)
    }

    ChatPage {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EncoText(stringResource(R.string.app_name), Modifier.weight(1f), size = 22.sp, weight = FontWeight.Bold)
            Row(
                modifier =
                    Modifier
                        .widthIn(max = 176.dp)
                        .clip(CircleShape)
                        .background(colors.panel)
                        .clickable(enabled = !state.generating, role = Role.Button, onClick = onModelSettings)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EncoText(
                    state.model.ifBlank { stringResource(R.string.model_settings) },
                    modifier = Modifier.weight(1f, fill = false),
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ControlIcon(ControlSymbol.NEXT, Modifier.size(13.dp), colors.muted)
            }
            ChatIconButton(ControlSymbol.PHONE, R.string.screen_control_title, onClick = onPhoneControl)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && !showPending) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(54.dp).background(colors.accentTint, CircleShape), contentAlignment = Alignment.Center) {
                        ControlIcon(ControlSymbol.CHAT, Modifier.size(25.dp), colors.accent)
                    }
                    EncoText(stringResource(R.string.chat_title), size = 24.sp, weight = FontWeight.Bold)
                    val hint =
                        when {
                            state.loading -> R.string.model_loading
                            state.model.isEmpty() -> R.string.chat_setup_hint
                            else -> R.string.chat_ready_hint
                        }
                    EncoText(stringResource(hint), color = colors.muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = list,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    if (showPending) {
                        if (state.reply.isNotEmpty()) {
                            item { ChatBubble(state.reply, user = false) }
                        } else if (state.generating) {
                            item {
                                EncoText(stringResource(R.string.chat_waiting), color = colors.muted, size = 13.sp)
                            }
                        }
                        item {
                            ChatBubble(state.pending, user = true)
                        }
                    }
                    items(state.messages.asReversed()) { message ->
                        ChatBubble(message.content, user = message.role == "user")
                    }
                }
            }
        }
        if (task.message != R.string.phone_task_idle) {
            EncoText(task.statusText(), Modifier.padding(bottom = 4.dp), size = 13.sp, color = colors.muted)
            if (task.detail.isNotBlank()) EncoText(task.detail, Modifier.padding(bottom = 8.dp), size = 13.sp)
        }
        state.chatNotice?.let { notice ->
            EncoText(stringResource(notice), Modifier.padding(bottom = 8.dp), size = 13.sp, color = colors.error)
        }
        val shape = RoundedCornerShape(25.dp)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(start = 15.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hint = stringResource(R.string.chat_placeholder)
            BasicTextField(
                value = state.draft,
                onValueChange = onEditDraft,
                modifier = Modifier.weight(1f).semantics { contentDescription = hint }.padding(vertical = 9.dp),
                enabled = !state.generating,
                maxLines = 5,
                textStyle = TextStyle(color = colors.text, fontSize = 15.sp, lineHeight = 21.sp),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { input ->
                    Box {
                        if (state.draft.isEmpty()) EncoText(hint, size = 15.sp, color = colors.muted)
                        input()
                    }
                },
            )
            if (task.running) {
                ChatIconButton(ControlSymbol.STOP, R.string.phone_task_stop, primary = true, onClick = onStopPhoneTask)
            }
            if (state.generating) {
                ChatIconButton(ControlSymbol.STOP, R.string.chat_stop, primary = true, onClick = onStop)
            } else {
                ChatIconButton(
                    ControlSymbol.SEND,
                    R.string.chat_send,
                    primary = true,
                    enabled = state.draft.isNotBlank() && !state.loading,
                    onClick = if (state.model.isBlank()) onModelSettings else onSend,
                )
            }
        }
    }
}

@Composable
internal fun ChatPage(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalEncoColors.current
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colorStops = arrayOf(0f to colors.backdrop, .68f to colors.background))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .imePadding()
                    .widthIn(max = 700.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            content = content,
        )
    }
}

@Composable
private fun ChatBubble(
    text: String,
    user: Boolean,
) {
    val colors = LocalEncoColors.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val modifier =
            if (user) {
                Modifier.widthIn(max = maxWidth * .85f).background(colors.accentTint, RoundedCornerShape(18.dp)).padding(12.dp)
            } else {
                Modifier.padding(horizontal = 3.dp)
            }
        SelectionContainer(modifier) { EncoText(text, size = 15.sp) }
    }
}

@Composable
internal fun ChatIconButton(
    symbol: ControlSymbol,
    label: Int,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val description = stringResource(label)
    val background = if (primary && enabled) colors.accent else colors.panel
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ControlIcon(symbol, Modifier.size(21.dp), if (primary && enabled) colors.onAccent else colors.muted)
    }
}
