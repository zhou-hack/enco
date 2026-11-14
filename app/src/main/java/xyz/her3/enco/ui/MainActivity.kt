package xyz.her3.enco.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.WeChatDraft
import xyz.her3.enco.core.agent.isPhoneTaskRequest
import xyz.her3.enco.core.agent.phoneTaskKind
import xyz.her3.enco.device.service.PhoneTaskReview
import xyz.her3.enco.device.service.PhoneTaskService
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.device.shizuku.ShizukuStatus
import xyz.her3.enco.ui.chat.ChatScreen
import xyz.her3.enco.ui.chat.ChatViewModel
import xyz.her3.enco.ui.confirm.PhoneTaskDialog
import xyz.her3.enco.ui.screen.ScreenControlService
import xyz.her3.enco.ui.settings.model.ModelSettingsScreen
import xyz.her3.enco.ui.theme.EncoTheme

class MainActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)
    private val chat by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncoTheme {
                var page by rememberSaveable { mutableStateOf(Page.CHAT) }
                var requestedGoal by rememberSaveable { mutableStateOf<String?>(null) }
                var waitingForShizuku by rememberSaveable { mutableStateOf<String?>(null) }
                var waitingForNotifications by rememberSaveable { mutableStateOf<String?>(null) }
                val state by chat.state.collectAsState()
                val task by PhoneTaskService.status.collectAsState()
                val runTask: (String) -> Unit = { goal ->
                    if (startPhoneTask(goal) && page == Page.CHAT) chat.editDraft("")
                }
                val notifications =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        val goal = waitingForNotifications
                        waitingForNotifications = null
                        if (goal != null) runTask(goal)
                    }
                val runApprovedTask: (String) -> Unit = { goal ->
                    if (phoneTaskKind(goal) == PhoneTaskKind.WECHAT_NAVIGATION && !overlayAllowed && shouldAskForTaskNotifications()) {
                        waitingForNotifications = goal
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runTask(goal)
                    }
                }
                val back = {
                    if (page == Page.MODELS) chat.cancelSettingsAction()
                    waitingForShizuku = null
                    page = Page.CHAT
                }
                BackHandler(enabled = page != Page.CHAT, onBack = back)
                when (page) {
                    Page.CHAT -> {
                        ChatScreen(
                            state = state,
                            onEditDraft = chat::editDraft,
                            onSend = {
                                if (isPhoneTaskRequest(state.draft)) {
                                    if (task.running) {
                                        Toast.makeText(this, R.string.phone_task_busy, Toast.LENGTH_SHORT).show()
                                    } else {
                                        requestedGoal = state.draft.trim()
                                    }
                                } else {
                                    chat.send()
                                }
                            },
                            onStop = chat::stop,
                            onModelSettings = {
                                chat.clearSettingsNotice()
                                page = Page.MODELS
                            },
                            onPhoneControl = { page = Page.PHONE },
                            task = task,
                            onRunPhoneTask = {
                                if (state.model.isBlank()) page = Page.MODELS else requestedGoal = state.draft.trim()
                            },
                            onStopPhoneTask = ::stopPhoneTask,
                        )
                    }

                    Page.MODELS -> {
                        ModelSettingsScreen(
                            state = state,
                            onBack = back,
                            onSubmit = { endpoint, model, key, testOnly ->
                                chat.submitSettings(endpoint, model, key, testOnly) { page = Page.CHAT }
                            },
                        )
                    }

                    Page.PHONE -> {
                        DisposableEffect(Unit) {
                            val observer =
                                LifecycleEventObserver { _, event ->
                                    if (event == Lifecycle.Event.ON_RESUME) ShizukuConnection.refresh()
                                }
                            lifecycle.addObserver(observer)
                            onDispose { lifecycle.removeObserver(observer) }
                        }
                        val status by ShizukuConnection.status.collectAsState()
                        LaunchedEffect(status, waitingForShizuku) {
                            if (status == ShizukuStatus.CONNECTED) {
                                waitingForShizuku?.let { goal ->
                                    waitingForShizuku = null
                                    page = Page.CHAT
                                    runApprovedTask(goal)
                                }
                            }
                        }
                        EncoHome(
                            status = status,
                            overlayAllowed = overlayAllowed,
                            onOpenChat = back,
                            onOverlaySettings = ::openOverlaySettings,
                            onOpenShizuku = ::openShizuku,
                            onRetry = { ShizukuConnection.refresh(requestPermission = true) },
                            onOpenScreenControl = ::openScreenControl,
                            task = task,
                            modelReady = state.model.isNotBlank(),
                            onStartTask = { requestedGoal = it },
                            onStopTask = ::stopPhoneTask,
                        )
                    }
                }
                requestedGoal?.let { goal ->
                    PhoneTaskDialog(
                        title = stringResource(R.string.phone_task_allow_title),
                        body =
                            stringResource(
                                when (phoneTaskKind(goal)) {
                                    PhoneTaskKind.WECHAT_NAVIGATION -> R.string.phone_task_allow_vision
                                    PhoneTaskKind.WECHAT_MESSAGE -> R.string.phone_task_allow_draft
                                    PhoneTaskKind.SETTINGS -> R.string.phone_task_allow_body
                                },
                                goal,
                            ),
                        confirmLabel = R.string.phone_task_allow,
                        onEnableOverlay = if (overlayAllowed) null else ::openOverlaySettings,
                        onCancel = { requestedGoal = null },
                        onConfirm = {
                            requestedGoal = null
                            if (phoneTaskKind(goal) == PhoneTaskKind.WECHAT_MESSAGE ||
                                ShizukuConnection.status.value == ShizukuStatus.CONNECTED
                            ) {
                                runApprovedTask(goal)
                            } else {
                                waitingForShizuku = goal
                                page = Page.PHONE
                                ShizukuConnection.refresh(requestPermission = true)
                                Toast.makeText(this, R.string.phone_task_setup, Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
                when (val review = task.review) {
                    is PhoneTaskReview.Draft -> {
                        PhoneTaskDialog(
                            title = stringResource(R.string.phone_task_draft_title),
                            body = stringResource(R.string.phone_task_draft_body, review.draft.recipient, review.draft.message),
                            confirmLabel = R.string.phone_task_open_wechat,
                            onCancel = PhoneTaskService::dismissDraft,
                            onConfirm = { openWeChatDraft(review.draft) },
                        )
                    }

                    is PhoneTaskReview.Tap -> {
                        PhoneTaskDialog(
                            title = stringResource(R.string.phone_task_review_tap),
                            body = stringResource(R.string.phone_task_review_tap_body, review.action.label),
                            confirmLabel = R.string.phone_task_allow_tap,
                            onCancel = { PhoneTaskService.answerTap(false) },
                            onConfirm = {
                                moveTaskToBack(true)
                                PhoneTaskService.answerTap(true)
                            },
                            preview = review.preview,
                            tap = review.action,
                        )
                    }

                    null -> {}
                }
            }
        }
    }

    private fun shouldAskForTaskNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun startPhoneTask(goal: String): Boolean {
        if (PhoneTaskService.status.value.running) return false
        if (goal.length > PhoneTaskService.MAX_GOAL_LENGTH) {
            Toast.makeText(this, R.string.phone_task_goal_length, Toast.LENGTH_LONG).show()
            return false
        }
        try {
            val kind = phoneTaskKind(goal)
            val target =
                when (kind) {
                    PhoneTaskKind.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
                    PhoneTaskKind.WECHAT_NAVIGATION -> packageManager.getLaunchIntentForPackage(PhoneTaskService.WECHAT_PACKAGE)
                    PhoneTaskKind.WECHAT_MESSAGE -> null
                }
            if ((target == null || target.resolveActivity(packageManager) == null) && kind != PhoneTaskKind.WECHAT_MESSAGE) {
                Toast.makeText(this, R.string.phone_task_screen, Toast.LENGTH_LONG).show()
                return false
            }
            stopService(Intent(this, ScreenControlService::class.java))
            startForegroundService(Intent(this, PhoneTaskService::class.java).putExtra(PhoneTaskService.GOAL, goal))
            if (target != null) startActivity(target)
            return true
        } catch (_: RuntimeException) {
            stopService(Intent(this, PhoneTaskService::class.java))
            Toast.makeText(this, R.string.phone_task_error, Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun stopPhoneTask() {
        stopService(Intent(this, PhoneTaskService::class.java))
    }

    private fun openWeChatDraft(draft: WeChatDraft) {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setClassName(PhoneTaskService.WECHAT_PACKAGE, "com.tencent.mm.ui.tools.ShareImgUI")
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, draft.message)
        try {
            startActivity(intent)
            PhoneTaskService.dismissDraft()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.phone_task_wechat_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openScreenControl() {
        try {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, ScreenControlService::class.java))
                moveTaskToBack(true)
            } else {
                openOverlaySettings()
            }
        } catch (error: RuntimeException) {
            Log.w("enco.device", "无法打开主屏操控", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
    }

    override fun onStart() {
        super.onStart()
        PhoneTaskService.setActivityVisible(true)
    }

    override fun onStop() {
        PhoneTaskService.setActivityVisible(false)
        super.onStop()
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
        } catch (error: ActivityNotFoundException) {
            Log.w("enco.device", "无法打开悬浮窗权限设置", error)
            Toast.makeText(this, R.string.screen_control_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openShizuku() {
        val launchIntent =
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        try {
            startActivity(launchIntent)
        } catch (error: ActivityNotFoundException) {
            Log.w("enco.shizuku", "无法打开 Shizuku", error)
            Toast.makeText(this, R.string.shizuku_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private enum class Page { CHAT, MODELS, PHONE }
}
