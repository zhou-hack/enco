package xyz.her3.enco.device.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneOperator
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.PhoneTaskPermission
import xyz.her3.enco.core.agent.PhoneTaskTool
import xyz.her3.enco.core.agent.TaskResult
import xyz.her3.enco.core.agent.VisionAction
import xyz.her3.enco.core.agent.WeChatDraft
import xyz.her3.enco.core.agent.WeChatMessageReview
import xyz.her3.enco.core.agent.WeChatTapDecision
import xyz.her3.enco.core.agent.WeChatTapReview
import xyz.her3.enco.core.agent.WeChatTask
import xyz.her3.enco.device.overlay.PhoneTaskOverlayService
import xyz.her3.enco.device.shell.IEncoShellService
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.llm.ChatClient
import xyz.her3.enco.llm.ChatException
import xyz.her3.enco.llm.ChatMessage
import xyz.her3.enco.llm.ModelConfig
import xyz.her3.enco.llm.ModelSettingsStore
import xyz.her3.enco.ui.MainActivity
import java.io.ByteArrayOutputStream

internal data class PhoneTaskStatus(
    val message: Int,
    val step: Int = 0,
    val detail: String = "",
    val running: Boolean = false,
    val review: PhoneTaskReview? = null,
    val progress: PhoneTaskProgress? = null,
    val taskId: String? = null,
    val canViewResult: Boolean = false,
) {
    val completed: Boolean get() = !running && message == R.string.phone_task_finished
}

internal data class PhoneTaskProgress(
    val app: Int,
    val message: Int,
    val target: String,
    val waiting: Boolean,
)

internal sealed interface PhoneTaskReview {
    data class Send(
        val message: WeChatDraft,
    ) : PhoneTaskReview

    data class Tap(
        val action: VisionAction.Tap,
        val preview: Bitmap,
    ) : PhoneTaskReview
}

class PhoneTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var taskId: String? = null
    private var appLabel = R.string.phone_task_app_settings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == STOP) {
            cancelTask()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        taskId = intent?.getStringExtra(TASK_ID)
        val goal = intent?.getStringExtra(GOAL)?.trim()
        val kind = intent?.getStringExtra(KIND)?.let { value -> PhoneTaskKind.entries.find { it.name == value } }
        val permission = intent?.getStringExtra(PERMISSION)?.let { value -> PhoneTaskPermission.entries.find { it.name == value } }
        if (goal == null || !PhoneTaskTool.validGoal(goal) || kind == null || permission == null) {
            update(PhoneTaskStatus(R.string.phone_task_error))
            stopSelf()
            return START_NOT_STICKY
        }

        appLabel =
            when (kind) {
                PhoneTaskKind.SETTINGS -> R.string.phone_task_app_settings
                PhoneTaskKind.GMAIL_INBOX -> R.string.phone_task_app_gmail
                else -> R.string.phone_task_app_wechat
            }
        val initialPhase =
            when (kind) {
                PhoneTaskKind.SETTINGS -> R.string.phone_task_phase_open_settings
                PhoneTaskKind.GMAIL_INBOX -> R.string.phone_task_phase_read_gmail
                PhoneTaskKind.WECHAT_MESSAGE, PhoneTaskKind.WECHAT_NAVIGATION -> R.string.phone_task_phase_open_wechat
            }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.phone_task_title), NotificationManager.IMPORTANCE_LOW),
        )
        update(PhoneTaskStatus(R.string.phone_task_running, running = true, progress = progress(initialPhase)))
        startForeground(NOTIFICATION, notification(status.value))
        job =
            scope.launch {
                try {
                    val config = ModelSettingsStore(this@PhoneTaskService).load()
                    if (config == null) {
                        update(PhoneTaskStatus(R.string.phone_task_model_required))
                        return@launch
                    }
                    val client = ChatClient()
                    val target =
                        when (kind) {
                            PhoneTaskKind.SETTINGS -> IEncoShellService.APP_SETTINGS
                            PhoneTaskKind.GMAIL_INBOX -> IEncoShellService.APP_GMAIL
                            PhoneTaskKind.WECHAT_MESSAGE, PhoneTaskKind.WECHAT_NAVIGATION -> IEncoShellService.APP_WECHAT
                        }
                    val result =
                        withTimeoutOrNull(600_000) {
                            if (!ShizukuConnection.withScreenService { it.launchApp(target) }) {
                                return@withTimeoutOrNull PhoneTaskStatus(R.string.phone_task_app_launch_failed)
                            }
                            when (kind) {
                                PhoneTaskKind.GMAIL_INBOX -> {
                                    readGmailInbox(goal)
                                }

                                PhoneTaskKind.WECHAT_MESSAGE -> {
                                    val message = readMessage(goal, client, config)
                                    if (message == null) {
                                        PhoneTaskStatus(R.string.phone_task_draft_missing)
                                    } else {
                                        runVision(goal, client, config, permission, message)
                                    }
                                }

                                PhoneTaskKind.WECHAT_NAVIGATION -> {
                                    runVision(goal, client, config, permission)
                                }

                                PhoneTaskKind.SETTINGS -> {
                                    delay(850)
                                    val phase =
                                        when {
                                            brightnessGoal.containsMatchIn(goal) -> R.string.phone_task_phase_find_brightness
                                            bluetoothGoal.containsMatchIn(goal) -> R.string.phone_task_phase_find_bluetooth
                                            else -> R.string.phone_task_phase_find_setting
                                        }
                                    var step = 0
                                    ShizukuConnection
                                        .withSettingsDevice { device ->
                                            PhoneOperator(device).run(
                                                goal,
                                                decide = { system, input ->
                                                    collectReply(
                                                        client.stream(
                                                            config,
                                                            listOf(
                                                                ChatMessage("system", system),
                                                                ChatMessage("user", input),
                                                            ),
                                                        ),
                                                    )
                                                },
                                                onStep = {
                                                    step = it
                                                    showStep(step, phase)
                                                },
                                                afterAction = { delay(650) },
                                                onAction = { label ->
                                                    if (label == null) {
                                                        showStep(step, R.string.phone_task_phase_scroll)
                                                    } else {
                                                        showStep(step, R.string.phone_task_phase_open_item, label)
                                                    }
                                                },
                                            )
                                        }.toStatus()
                                }
                            }
                        }
                    val finalStatus = result ?: PhoneTaskStatus(R.string.phone_task_limit)
                    update(finalStatus)
                    if (finalStatus.completed) showCompletion(kind)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("enco.agent", "手机任务中断：${error.javaClass.simpleName}")
                    update(PhoneTaskStatus(R.string.phone_task_error))
                } finally {
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    private suspend fun readMessage(
        goal: String,
        client: ChatClient,
        config: ModelConfig,
    ): WeChatDraft? {
        val reply =
            collectReply(
                client.stream(
                    config,
                    listOf(
                        ChatMessage("system", WeChatTask.draftPrompt),
                        ChatMessage("user", goal),
                    ),
                ),
            )
        return WeChatTask.parseDraft(reply)
    }

    private suspend fun readGmailInbox(goal: String): PhoneTaskStatus {
        delay(850)
        val count =
            Regex("(?<![0-9])[0-9]{1,3}(?![0-9])")
                .find(goal)
                ?.value
                ?.toIntOrNull()
                ?.coerceIn(1, 20) ?: 10
        val rows =
            ShizukuConnection
                .withScreenService { it.readGmailInbox(count) }
                ?.asList()
                ?.chunked(3)
                .orEmpty()
        if (rows.isEmpty()) return PhoneTaskStatus(R.string.phone_task_gmail_unavailable)
        val result =
            rows
                .mapIndexed { index, (sender, subject, date) ->
                    getString(
                        R.string.phone_task_gmail_row,
                        index + 1,
                        sender,
                        subject.ifBlank { getString(R.string.phone_task_gmail_no_subject) },
                        date,
                    )
                }.joinToString("\n\n")
        val title = getString(R.string.phone_task_gmail_count, rows.size, count)
        return PhoneTaskStatus(
            if (rows.size == count) R.string.phone_task_finished else R.string.phone_task_not_finished,
            detail = "$title\n\n$result",
        )
    }

    private suspend fun runVision(
        goal: String,
        client: ChatClient,
        config: ModelConfig,
        permission: PhoneTaskPermission,
        message: WeChatDraft? = null,
    ): PhoneTaskStatus {
        delay(900)
        var feedback = ""
        var previousImage = ""
        var unchanged = 0
        var sent = false
        var searched = false
        var composed = false
        val unverified = PhoneTaskStatus(R.string.phone_task_wechat_unverified)
        repeat(40) { index ->
            showStep(index + 1, R.string.phone_task_phase_read_wechat)
            val frame = PhoneTaskOverlayService.withoutOverlay { ShizukuConnection.withScreenService { it.captureScreen() } }
            if (frame.packageName != WECHAT_PACKAGE) {
                return if (sent) unverified else PhoneTaskStatus(R.string.phone_task_takeover)
            }
            val image = frame.image?.softwareImage() ?: return PhoneTaskStatus(R.string.phone_task_screen)
            val fingerprint = withContext(Dispatchers.Default) { image.fingerprint() }
            if (fingerprint.isEmpty()) return PhoneTaskStatus(R.string.phone_task_takeover)
            unchanged = if (fingerprint == previousImage) unchanged + 1 else 0
            if (unchanged >= 3) return PhoneTaskStatus(R.string.phone_task_stalled)
            previousImage = fingerprint
            val imageUrl =
                withContext(Dispatchers.Default) {
                    val bytes = ByteArrayOutputStream()
                    check(image.compress(Bitmap.CompressFormat.JPEG, 80, bytes))
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            val action =
                try {
                    decideVision(client, config, goal, feedback, imageUrl, message)
                } catch (error: ChatException) {
                    if (error.status !in setOf(400, 413, 415, 422)) throw error
                    return PhoneTaskStatus(R.string.phone_task_vision_error)
                } ?: return if (sent) unverified else PhoneTaskStatus(R.string.phone_task_reply)
            if (sent) {
                val verified =
                    action is VisionAction.Finish && action.success && message != null &&
                        reviewMessage(client, config, message, imageUrl, "finish") == WeChatMessageReview.SENT
                return if (verified) {
                    PhoneTaskStatus(
                        R.string.phone_task_finished,
                        detail = getString(R.string.phone_task_wechat_sent),
                    )
                } else {
                    unverified
                }
            }
            when (action) {
                is VisionAction.Finish -> {
                    if (message != null) return PhoneTaskStatus(R.string.phone_task_takeover)
                    return TaskResult.Finished(action.success, action.summary).toStatus()
                }

                VisionAction.TakeOver -> {
                    return PhoneTaskStatus(R.string.phone_task_takeover)
                }

                is VisionAction.Tap -> {
                    if (message != null) {
                        if (WeChatTask.needsManualAction(action.label) ||
                            reviewMessage(client, config, message, imageUrl, "tap", action) != WeChatMessageReview.NAVIGATION
                        ) {
                            return PhoneTaskStatus(R.string.phone_task_takeover)
                        }
                    } else {
                        val decision =
                            WeChatTask.tapDecision(permission, action) {
                                reviewVisionTarget(client, config, action, imageUrl)
                            }
                        if (decision == WeChatTapDecision.TAKE_OVER) return PhoneTaskStatus(R.string.phone_task_takeover)
                        if (decision == WeChatTapDecision.CONFIRM) {
                            if (!reviewTap(action, image)) return PhoneTaskStatus(R.string.phone_task_stopped)
                            delay(950)
                        }
                    }
                    showStep(index + 1, R.string.phone_task_phase_tap)
                    val applied =
                        onUnchangedWeChatFrame(image, action) { id ->
                            ShizukuConnection.withScreenService { it.tap(id, action.x, action.y) }
                        }
                    feedback = if (applied) "已点按所选位置，检查新画面。" else "画面变化或点按失败，重新观察。"
                }

                is VisionAction.Scroll -> {
                    val from = if (action.up) 730 else 340
                    val to = if (action.up) 340 else 730
                    showStep(index + 1, R.string.phone_task_phase_scroll)
                    val applied =
                        PhoneTaskOverlayService.withoutOverlay {
                            val current = ShizukuConnection.withScreenService { it.captureScreen() }
                            val currentImage = current.image?.softwareImage()
                            val stillCurrent = withContext(Dispatchers.Default) { currentImage?.fingerprint() == fingerprint }
                            current.packageName == WECHAT_PACKAGE && stillCurrent &&
                                ShizukuConnection.withScreenService { it.swipe(current.id, 500, from, 500, to) }
                        }
                    feedback = if (applied) "已滑动，检查新画面。" else "滑动未执行，请重新观察。"
                }

                is VisionAction.Type -> {
                    checkNotNull(message)
                    if (if (action.recipient) searched else composed) return PhoneTaskStatus(R.string.phone_task_takeover)
                    val command = if (action.recipient) "type_recipient" else "type_message"
                    val expected = if (action.recipient) WeChatMessageReview.RECIPIENT_FIELD else WeChatMessageReview.MESSAGE_FIELD
                    if (reviewMessage(client, config, message, imageUrl, command) != expected) {
                        return PhoneTaskStatus(R.string.phone_task_takeover)
                    }
                    showStep(
                        index + 1,
                        if (action.recipient) R.string.phone_task_phase_search_contact else R.string.phone_task_phase_edit_message,
                    )
                    val text = if (action.recipient) message.recipient else message.message
                    val applied =
                        onUnchangedWeChatFrame(image) { id ->
                            ShizukuConnection.withScreenService { it.pasteWeChatText(id, text) }
                        }
                    if (applied) {
                        if (action.recipient) searched = true else composed = true
                        feedback = if (action.recipient) "已尝试粘贴收件人，请核对搜索结果。" else "已尝试粘贴正文，请核对收件人、正文和发送按钮。"
                    } else {
                        feedback = "粘贴未执行或无法核实，重新检查微信是否仍在前台、输入框是否聚焦。"
                    }
                }

                is VisionAction.Send -> {
                    checkNotNull(message)
                    val target = VisionAction.Tap(action.x, action.y, "")
                    if (!composed || reviewMessage(client, config, message, imageUrl, "send", target) != WeChatMessageReview.SEND_BUTTON) {
                        return PhoneTaskStatus(R.string.phone_task_takeover)
                    }
                    if (permission == PhoneTaskPermission.IMPORTANT_ONLY) {
                        val state =
                            PhoneTaskStatus(
                                R.string.phone_task_confirm_send,
                                running = true,
                                review = PhoneTaskReview.Send(message),
                                progress = progress(R.string.phone_task_confirm_send, waiting = true),
                            )
                        if (!awaitConfirmation(state)) return PhoneTaskStatus(R.string.phone_task_stopped)
                        delay(950)
                    }
                    showStep(index + 1, R.string.phone_task_phase_send)
                    val applied =
                        onUnchangedWeChatFrame(image, target, exact = true) { id ->
                            ShizukuConnection.withScreenService { it.tap(id, action.x, action.y) }
                        }
                    if (!applied) return unverified
                    sent = true
                    feedback = "已点按一次发送。只检查结果，不要再次发送。"
                }
            }
            delay(650)
        }
        return PhoneTaskStatus(R.string.phone_task_limit)
    }

    private suspend fun onUnchangedWeChatFrame(
        image: Bitmap,
        tap: VisionAction.Tap? = null,
        exact: Boolean = false,
        action: suspend (Long) -> Boolean,
    ): Boolean =
        PhoneTaskOverlayService.withoutOverlay {
            val current = ShizukuConnection.withScreenService { it.captureScreen() }
            if (current.packageName != WECHAT_PACKAGE) return@withoutOverlay false
            val currentImage = current.image?.softwareImage() ?: return@withoutOverlay false
            val unchanged =
                withContext(Dispatchers.Default) {
                    if (exact) {
                        image.sameContent(currentImage)
                    } else {
                        image.similarTo(currentImage) && (tap == null || image.sameTapArea(currentImage, tap))
                    }
                }
            currentCoroutineContext().ensureActive()
            unchanged && action(current.id)
        }

    private suspend fun reviewTap(
        action: VisionAction.Tap,
        image: Bitmap,
    ): Boolean =
        awaitConfirmation(
            PhoneTaskStatus(
                R.string.phone_task_review_tap,
                running = true,
                review = PhoneTaskReview.Tap(action, image),
                progress = progress(R.string.phone_task_phase_wait_tap, waiting = true),
            ),
        )

    private suspend fun awaitConfirmation(state: PhoneTaskStatus): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingTap = answer
        show(state)
        if (!PhoneTaskOverlayService.isVisible && !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            Toast.makeText(this, R.string.phone_task_return_to_enco, Toast.LENGTH_LONG).show()
        }
        return try {
            answer.await()
        } finally {
            pendingTap = null
        }
    }

    private suspend fun reviewMessage(
        client: ChatClient,
        config: ModelConfig,
        message: WeChatDraft,
        image: String,
        action: String,
        target: VisionAction.Tap? = null,
    ): WeChatMessageReview {
        val input =
            buildJsonObject {
                put("recipient", message.recipient)
                put("message", message.message)
                put("action", action)
                if (target != null) {
                    put("x", target.x)
                    put("y", target.y)
                }
            }
        return WeChatTask.parseMessageReview(
            collectReply(client.streamImage(config, WeChatTask.messageGuardPrompt, input.toString(), image)),
        )
    }

    private suspend fun reviewVisionTarget(
        client: ChatClient,
        config: ModelConfig,
        action: VisionAction.Tap,
        image: String,
    ): WeChatTapReview {
        val reply =
            collectReply(
                client.streamImage(
                    config,
                    WeChatTask.tapGuardPrompt,
                    "目标：${action.label}\n坐标：${action.x},${action.y}",
                    image,
                ),
            )
        return WeChatTask.parseTapReview(reply)
    }

    private suspend fun decideVision(
        client: ChatClient,
        config: ModelConfig,
        goal: String,
        feedback: String,
        image: String,
        message: WeChatDraft?,
    ): VisionAction? {
        var note = feedback
        repeat(3) {
            val input =
                if (message == null) {
                    "goal: $goal\n上一步: $note"
                } else {
                    buildJsonObject {
                        put("recipient", message.recipient)
                        put("message", message.message)
                        put("previous_step", note)
                    }.toString()
                }
            val prompt = if (message == null) WeChatTask.visionPrompt else WeChatTask.messagePrompt
            val reply = collectReply(client.streamImage(config, prompt, input, image))
            WeChatTask.parseVision(reply, allowMessage = message != null)?.let { return it }
            note = "上一次输出无法解析，请只返回动作 JSON。"
        }
        return null
    }

    private suspend fun collectReply(stream: kotlinx.coroutines.flow.Flow<String>): String {
        val reply = StringBuilder()
        stream.collect {
            check(reply.length + it.length <= 8192) { "动作回复超出长度限制" }
            reply.append(it)
        }
        return reply.toString()
    }

    private fun progress(
        message: Int,
        target: String = "",
        waiting: Boolean = false,
    ) = PhoneTaskProgress(appLabel, message, target, waiting)

    private fun showStep(
        step: Int,
        message: Int,
        target: String = "",
    ) = show(PhoneTaskStatus(R.string.phone_task_step, step, running = true, progress = progress(message, target)))

    private fun show(state: PhoneTaskStatus) {
        update(state)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
    }

    private suspend fun Bitmap.softwareImage(): Bitmap? =
        withContext(Dispatchers.Default) {
            if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this@softwareImage
        }

    private fun Bitmap.fingerprint(): String {
        val sample = Bitmap.createScaledBitmap(this, 32, 48, true)
        val pixels = IntArray(32 * 44)
        try {
            sample.getPixels(pixels, 0, 32, 0, 2, 32, 44)
            if (pixels.all { (it and 0xFFFFFF) < 0x030303 }) return ""
            return pixels.joinToString(",") { (it and 0x00F0F0F0).toString() }
        } finally {
            if (sample !== this) sample.recycle()
        }
    }

    private fun Bitmap.similarTo(other: Bitmap): Boolean {
        if (width != other.width || height != other.height) return false
        val first = Bitmap.createScaledBitmap(this, 48, 80, true)
        val second = Bitmap.createScaledBitmap(other, 48, 80, true)
        return try {
            var changes = 0
            for (y in 4 until 79) {
                for (x in 0 until 48) {
                    val a = first.getPixel(x, y)
                    val b = second.getPixel(x, y)
                    if (kotlin.math.abs((a and 255) - (b and 255)) > 16 && ++changes > 24) return false
                }
            }
            true
        } finally {
            if (first !== this) first.recycle()
            if (second !== other) second.recycle()
        }
    }

    private fun Bitmap.sameContent(other: Bitmap): Boolean {
        if (width != other.width || height != other.height) return false
        val top = height / 25
        val expected = IntArray(width)
        val actual = IntArray(width)
        for (y in top until height) {
            getPixels(expected, 0, width, 0, y, width, 1)
            other.getPixels(actual, 0, width, 0, y, width, 1)
            if (!expected.contentEquals(actual)) return false
        }
        return true
    }

    private fun Bitmap.sameTapArea(
        other: Bitmap,
        tap: VisionAction.Tap,
    ): Boolean {
        if (width != other.width || height != other.height) return false
        val x = tap.x * (width - 1) / 1000
        val y = tap.y * (height - 1) / 1000
        val radius = 18
        val left = (x - radius).coerceAtLeast(0)
        val top = (y - radius).coerceAtLeast(0)
        val regionWidth = minOf(width - left, radius * 2 + 1)
        val regionHeight = minOf(height - top, radius * 2 + 1)
        val expected = IntArray(regionWidth * regionHeight)
        val actual = IntArray(expected.size)
        getPixels(expected, 0, regionWidth, left, top, regionWidth, regionHeight)
        other.getPixels(actual, 0, regionWidth, left, top, regionWidth, regionHeight)
        return expected.contentEquals(actual)
    }

    private fun cancelTask() {
        job?.cancel()
        if (status.value.running && status.value.taskId == taskId) update(PhoneTaskStatus(R.string.phone_task_stopped))
        stopSelf()
    }

    override fun onDestroy() {
        if (status.value.running && status.value.taskId == taskId) update(PhoneTaskStatus(R.string.phone_task_stopped))
        scope.cancel()
        super.onDestroy()
    }

    private fun taskActivityIntent(): Intent =
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun showCompletion(kind: PhoneTaskKind) {
        // 系统设置可以隐藏第三方悬浮窗，完成提示需要走系统界面。
        if (kind != PhoneTaskKind.SETTINGS && Settings.canDrawOverlays(this)) return
        val manager = getSystemService(NotificationManager::class.java)
        val title = getString(R.string.phone_task_overlay_completed)
        manager.createNotificationChannel(
            NotificationChannel(COMPLETION_CHANNEL, title, NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
        if (!manager.areNotificationsEnabled() ||
            (manager.getNotificationChannel(COMPLETION_CHANNEL)?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH
        ) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
            return
        }
        manager.notify(
            COMPLETION_NOTIFICATION,
            Notification
                .Builder(this, COMPLETION_CHANNEL)
                .setSmallIcon(android.R.drawable.checkbox_on_background)
                .setColor(Color.rgb(60, 167, 115))
                .setContentTitle(title)
                .setTimeoutAfter(5_000)
                .build(),
        )
    }

    private fun notification(state: PhoneTaskStatus): Notification {
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, PhoneTaskService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                taskActivityIntent(),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.phone_task_title))
            .setContentText(
                state.progress?.let { progress ->
                    val phase = if (progress.target.isEmpty()) getString(progress.message) else getString(progress.message, progress.target)
                    getString(R.string.phone_task_progress_line, getString(progress.app), phase)
                } ?: getString(state.message),
            ).setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.phone_task_stop), stop).build())
            .build()
    }

    companion object {
        internal const val TASK_ID = "task_id"
        internal const val GOAL = "goal"
        internal const val KIND = "kind"
        internal const val PERMISSION = "permission"
        internal const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val STOP = "xyz.her3.enco.action.STOP_PHONE_TASK"
        private const val CHANNEL = "phone_task"
        private const val NOTIFICATION = 2
        private const val COMPLETION_CHANNEL = "phone_task_completion"
        private const val COMPLETION_NOTIFICATION = 4
        private val brightnessGoal = Regex("亮度|brightness", RegexOption.IGNORE_CASE)
        private val bluetoothGoal = Regex("蓝牙|藍牙|bluetooth", RegexOption.IGNORE_CASE)
        private val mutableStatus = MutableStateFlow(PhoneTaskStatus(R.string.phone_task_idle))
        internal val status = mutableStatus.asStateFlow()
        private var pendingTap: CompletableDeferred<Boolean>? = null

        internal fun answerTap(allow: Boolean) {
            pendingTap?.complete(allow)
        }

        internal fun offerResult(taskId: String) {
            val current = status.value
            if (current.taskId == taskId && !current.running) {
                mutableStatus.value = current.copy(canViewResult = true)
            }
        }
    }

    private fun update(state: PhoneTaskStatus) {
        mutableStatus.value = state.copy(taskId = taskId)
    }
}

private fun TaskResult?.toStatus(): PhoneTaskStatus =
    when (this) {
        is TaskResult.Finished -> {
            PhoneTaskStatus(
                if (success) R.string.phone_task_finished else R.string.phone_task_not_finished,
                detail = summary,
            )
        }

        TaskResult.TakeOver -> {
            PhoneTaskStatus(R.string.phone_task_takeover)
        }

        TaskResult.ScreenUnavailable -> {
            PhoneTaskStatus(R.string.phone_task_screen)
        }

        TaskResult.InvalidReply -> {
            PhoneTaskStatus(R.string.phone_task_reply)
        }

        TaskResult.Stalled -> {
            PhoneTaskStatus(R.string.phone_task_stalled)
        }

        TaskResult.StepLimit, null -> {
            PhoneTaskStatus(R.string.phone_task_limit)
        }
    }
