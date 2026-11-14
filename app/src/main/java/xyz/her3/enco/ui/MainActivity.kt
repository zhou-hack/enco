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
import xyz.her3.enco.core.agent.PhoneTaskPermission
import xyz.her3.enco.core.agent.PhoneTaskRequest
import xyz.her3.enco.core.agent.PhoneTaskRouter
import xyz.her3.enco.device.overlay.PhoneTaskOverlayService
import xyz.her3.enco.device.service.PhoneTaskReview
import xyz.her3.enco.device.service.PhoneTaskService
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.device.shizuku.ShizukuStatus
import xyz.her3.enco.ui.chat.ChatScreen
import xyz.her3.enco.ui.chat.ChatViewModel
import xyz.her3.enco.ui.confirm.PhoneTaskDialog
import xyz.her3.enco.ui.confirm.PhoneTaskPermissionDialog
import xyz.her3.enco.ui.screen.ScreenControlService
import xyz.her3.enco.ui.settings.model.ModelSettingsScreen
import xyz.her3.enco.ui.theme.EncoTheme

class MainActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)
    private var activityResumed by mutableStateOf(false)
    private val chat by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncoTheme {
                var page by rememberSaveable { mutableStateOf(Page.CHAT) }
                var waitingForShizuku by rememberSaveable { mutableStateOf<ApprovedTask?>(null) }
                var waitingForNotifications by rememberSaveable { mutableStateOf<ApprovedTask?>(null) }
                val state by chat.state.collectAsState()
                val task by PhoneTaskService.status.collectAsState()
                val runTask: (ApprovedTask) -> Unit = { approved ->
                    chat.dismissPhoneRequest(restoreDraft = !startPhoneTask(approved))
                }
                val notifications =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        val goal = waitingForNotifications
                        waitingForNotifications = null
                        if (goal != null) runTask(goal)
                    }
                val runApprovedTask: (ApprovedTask) -> Unit = { approved ->
                    if (approved.request.kind in setOf(PhoneTaskKind.WECHAT_NAVIGATION, PhoneTaskKind.WECHAT_MESSAGE) &&
                        approved.permission == PhoneTaskPermission.IMPORTANT_ONLY &&
                        !overlayAllowed && shouldAskForTaskNotifications()
                    ) {
                        waitingForNotifications = approved
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runTask(approved)
                    }
                }
                val back = {
                    if (page == Page.MODELS) chat.cancelSettingsAction()
                    if (waitingForShizuku != null) chat.dismissPhoneRequest(restoreDraft = true)
                    waitingForShizuku = null
                    page = Page.CHAT
                }
                BackHandler(enabled = page != Page.CHAT, onBack = back)
                when (page) {
                    Page.CHAT -> {
                        ChatScreen(
                            state = state,
                            onEditDraft = chat::editDraft,
                            onSend = chat::send,
                            onStop = chat::stop,
                            onModelSettings = {
                                chat.clearSettingsNotice()
                                page = Page.MODELS
                            },
                            onPhoneControl = { page = Page.PHONE },
                            task = task,
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
                        LaunchedEffect(status, waitingForShizuku, activityResumed) {
                            if (status == ShizukuStatus.CONNECTED && activityResumed) {
                                waitingForShizuku?.let { approved ->
                                    waitingForShizuku = null
                                    page = Page.CHAT
                                    runApprovedTask(approved)
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
                            modelReady = state.model.isNotBlank() && !state.generating && state.phoneRequest == null,
                            onStartTask = {
                                page = Page.CHAT
                                chat.editDraft(it)
                                chat.send()
                            },
                            onStopTask = ::stopPhoneTask,
                        )
                    }
                }
                val request = state.phoneRequest
                if (request != null && waitingForShizuku == null && waitingForNotifications == null && !state.generating) {
                    if (task.running) {
                        LaunchedEffect(request) {
                            chat.dismissPhoneRequest(restoreDraft = true, notice = R.string.phone_task_busy)
                        }
                    } else {
                        PhoneTaskPermissionDialog(
                            body =
                                stringResource(
                                    when (request.kind) {
                                        PhoneTaskKind.WECHAT_NAVIGATION -> R.string.phone_task_allow_vision
                                        PhoneTaskKind.WECHAT_MESSAGE -> R.string.phone_task_allow_wechat_send
                                        PhoneTaskKind.GMAIL_INBOX -> R.string.phone_task_allow_gmail
                                        PhoneTaskKind.SETTINGS -> R.string.phone_task_allow_body
                                    },
                                    request.goal,
                                ),
                            onEnableOverlay = if (overlayAllowed) null else ::openOverlaySettings,
                            onCancel = { chat.dismissPhoneRequest(restoreDraft = true, notice = R.string.phone_task_permission_cancelled) },
                            onAllow = { permission ->
                                val approved = ApprovedTask(request, permission)
                                if (ShizukuConnection.status.value == ShizukuStatus.CONNECTED) {
                                    runApprovedTask(approved)
                                } else {
                                    waitingForShizuku = approved
                                    page = Page.PHONE
                                    ShizukuConnection.refresh(requestPermission = true)
                                    Toast.makeText(this, R.string.phone_task_setup, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                }
                when (val review = task.review) {
                    is PhoneTaskReview.Send -> {
                        PhoneTaskDialog(
                            title = stringResource(R.string.phone_task_confirm_send),
                            body = stringResource(R.string.phone_task_send_body, review.message.recipient, review.message.message),
                            confirmLabel = R.string.phone_task_send,
                            onCancel = { PhoneTaskService.answerTap(false) },
                            onConfirm = {
                                moveTaskToBack(true)
                                PhoneTaskService.answerTap(true)
                            },
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

    private fun startPhoneTask(approved: ApprovedTask): Boolean {
        if (PhoneTaskService.status.value.running) return false
        val (kind, goal) = approved.request
        if (goal.length > PhoneTaskRouter.MAX_GOAL_LENGTH) {
            Toast.makeText(this, R.string.phone_task_goal_length, Toast.LENGTH_LONG).show()
            return false
        }
        try {
            val target =
                when (kind) {
                    PhoneTaskKind.GMAIL_INBOX -> {
                        packageManager.getLaunchIntentForPackage(PhoneTaskService.GMAIL_PACKAGE)
                    }

                    PhoneTaskKind.SETTINGS -> {
                        Intent(Settings.ACTION_SETTINGS)
                    }

                    PhoneTaskKind.WECHAT_NAVIGATION, PhoneTaskKind.WECHAT_MESSAGE -> {
                        packageManager.getLaunchIntentForPackage(PhoneTaskService.WECHAT_PACKAGE)
                    }
                }
            if (target == null || target.resolveActivity(packageManager) == null) {
                Toast.makeText(this, R.string.phone_task_screen, Toast.LENGTH_LONG).show()
                return false
            }
            stopService(Intent(this, ScreenControlService::class.java))
            PhoneTaskOverlayService.startIfAllowed(this)
            startForegroundService(
                Intent(this, PhoneTaskService::class.java)
                    .putExtra(PhoneTaskService.GOAL, goal)
                    .putExtra(PhoneTaskService.KIND, kind.name)
                    .putExtra(PhoneTaskService.PERMISSION, approved.permission.name),
            )
            startActivity(target)
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
        activityResumed = true
        overlayAllowed = Settings.canDrawOverlays(this)
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
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

    private data class ApprovedTask(
        val request: PhoneTaskRequest,
        val permission: PhoneTaskPermission,
    ) : java.io.Serializable
}
