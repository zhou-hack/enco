package xyz.her3.enco.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.PhoneTaskPermission
import xyz.her3.enco.core.agent.PhoneTaskRequest
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
    private var notificationsAllowed by mutableStateOf(false)
    private var permissionsGranting by mutableStateOf(false)
    private var activityResumed by mutableStateOf(false)
    private val chat by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncoTheme {
                var page by rememberSaveable { mutableStateOf(Page.CHAT) }
                var waitingForShizuku by rememberSaveable { mutableStateOf<ApprovedTask?>(null) }
                val state by chat.state.collectAsState()
                val task by PhoneTaskService.status.collectAsState()
                val runTask: (ApprovedTask) -> Unit = { approved ->
                    chat.approvePhoneRequest(approved.request) { taskId -> startPhoneTask(approved, taskId) }
                }
                val back = {
                    if (page == Page.MODELS) chat.cancelSettingsAction()
                    if (waitingForShizuku != null) chat.declinePhoneRequest()
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
                                    runTask(approved)
                                }
                            }
                        }
                        EncoHome(
                            status = status,
                            overlayAllowed = overlayAllowed,
                            notificationsAllowed = notificationsAllowed,
                            permissionsGranting = permissionsGranting,
                            onOpenChat = back,
                            onGrantPermissions = ::grantPermissions,
                            onOverlaySettings = ::openOverlaySettings,
                            onNotificationSettings = ::openNotificationSettings,
                            onOpenShizuku = ::openShizuku,
                            onRetry = { ShizukuConnection.refresh(requestPermission = true) },
                            onOpenScreenControl = ::openScreenControl,
                            task = task,
                            modelReady = state.model.isNotBlank() && !state.generating && !state.phoneTurn,
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
                if (request != null && waitingForShizuku == null && !state.generating) {
                    if (task.running) {
                        LaunchedEffect(request) {
                            chat.declinePhoneRequest(R.string.phone_task_busy)
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
                            onCancel = { chat.declinePhoneRequest() },
                            onAllow = { permission ->
                                val approved = ApprovedTask(request, permission)
                                if (ShizukuConnection.status.value == ShizukuStatus.CONNECTED) {
                                    runTask(approved)
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

    private fun startPhoneTask(
        approved: ApprovedTask,
        taskId: String,
    ): Boolean {
        val (kind, goal) = approved.request
        try {
            stopService(Intent(this, ScreenControlService::class.java))
            PhoneTaskOverlayService.startIfAllowed(this)
            startForegroundService(
                Intent(this, PhoneTaskService::class.java)
                    .putExtra(PhoneTaskService.TASK_ID, taskId)
                    .putExtra(PhoneTaskService.GOAL, goal)
                    .putExtra(PhoneTaskService.KIND, kind.name)
                    .putExtra(PhoneTaskService.PERMISSION, approved.permission.name),
            )
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
        refreshPermissionState()
        PhoneTaskOverlayService.startIfAllowed(this)
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun refreshPermissionState() {
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    private fun grantPermissions() {
        if (permissionsGranting || ShizukuConnection.status.value != ShizukuStatus.CONNECTED) return
        permissionsGranting = true
        lifecycleScope.launch {
            var failed = false
            try {
                ShizukuConnection.withScreenService { it.grantSelfPermissions() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                Log.w("enco.shizuku", "一键授权调用失败", error)
            } finally {
                refreshPermissionState()
                permissionsGranting = false
            }
            PhoneTaskOverlayService.startIfAllowed(this@MainActivity)
            if (failed || !overlayAllowed || !notificationsAllowed) {
                Toast.makeText(this@MainActivity, R.string.screen_control_grant_incomplete, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotificationSettings() {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Log.w("enco.device", "无法打开通知权限设置", error)
            Toast.makeText(this, R.string.screen_control_notification_settings_failed, Toast.LENGTH_LONG).show()
        }
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
