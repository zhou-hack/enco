package xyz.her3.enco.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.her3.enco.R
import xyz.her3.enco.device.service.PhoneTaskProgress
import xyz.her3.enco.device.service.PhoneTaskStatus

@Composable
internal fun PhoneTaskProgress.phaseText(): String = if (target.isEmpty()) stringResource(message) else stringResource(message, target)

@Composable
internal fun PhoneTaskStatus.statusText(): String =
    progress?.let { stringResource(R.string.phone_task_progress_line, stringResource(it.app), it.phaseText()) }
        ?: if (step > 0) stringResource(message, step) else stringResource(message)
