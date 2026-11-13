package xyz.her3.enco.ui.settings.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.her3.enco.R
import xyz.her3.enco.ui.ControlSymbol
import xyz.her3.enco.ui.chat.ChatIconButton
import xyz.her3.enco.ui.chat.ChatPage
import xyz.her3.enco.ui.chat.ChatState
import xyz.her3.enco.ui.theme.EncoText
import xyz.her3.enco.ui.theme.LocalEncoColors

@Composable
internal fun ModelSettingsScreen(
    state: ChatState,
    onBack: () -> Unit,
    onSubmit: (String, String, String, Boolean) -> Unit,
) {
    val colors = LocalEncoColors.current
    var url by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var apiKey by remember { mutableStateOf("") }
    val enabled = !state.loading && !state.settingsBusy

    ChatPage {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChatIconButton(ControlSymbol.BACK, R.string.screen_control_back, onClick = onBack)
            EncoText(stringResource(R.string.model_settings), size = 22.sp, weight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.panel, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ModelField(R.string.model_base_url, url, { url = it }, enabled, keyboardType = KeyboardType.Uri)
                ModelField(R.string.model_name, model, { model = it }, enabled)
                ModelField(R.string.model_api_key, apiKey, { apiKey = it }, enabled, keyboardType = KeyboardType.Password)
                val keyHint = if (state.hasSavedKey) R.string.model_keep_key else R.string.model_key_optional
                EncoText(stringResource(keyHint), color = colors.muted, size = 12.sp)
                if (state.endpoint.isNotEmpty()) {
                    EncoText(stringResource(R.string.model_change_endpoint), color = colors.muted, size = 12.sp)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsButton(R.string.model_test, enabled, primary = false) { onSubmit(url, model, apiKey, true) }
                SettingsButton(R.string.model_save, enabled, primary = true) { onSubmit(url, model, apiKey, false) }
            }
            val notice = if (state.settingsBusy) R.string.model_working else state.settingsNotice
            if (notice != null) {
                EncoText(
                    stringResource(notice),
                    color =
                        when (notice) {
                            R.string.model_test_success -> colors.positive
                            R.string.model_working -> colors.muted
                            else -> colors.error
                        },
                )
            }
        }
    }
}

@Composable
private fun ModelField(
    label: Int,
    value: String,
    onEdit: (String) -> Unit,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = LocalEncoColors.current
    val fieldLabel = stringResource(label)
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EncoText(fieldLabel, weight = FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onEdit,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            visualTransformation =
                if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = colors.text, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.inset, shape)
                    .border(1.dp, colors.line, shape)
                    .semantics { contentDescription = fieldLabel }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
        )
    }
}

@Composable
private fun SettingsButton(
    label: Int,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalEncoColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier =
            Modifier
                .clip(shape)
                .background(if (primary && enabled) colors.accent else colors.panel)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        EncoText(
            stringResource(label),
            color = if (primary && enabled) colors.onAccent else colors.muted,
            weight = FontWeight.SemiBold,
        )
    }
}
