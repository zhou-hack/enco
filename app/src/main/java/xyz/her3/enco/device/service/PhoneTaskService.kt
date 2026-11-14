package xyz.her3.enco.device.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneOperator
import xyz.her3.enco.core.agent.PhoneTaskKind
import xyz.her3.enco.core.agent.TaskResult
import xyz.her3.enco.core.agent.VisionAction
import xyz.her3.enco.core.agent.WeChatDraft
import xyz.her3.enco.core.agent.WeChatTask
import xyz.her3.enco.core.agent.phoneTaskKind
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
)

internal sealed interface PhoneTaskReview {
    data class Draft(
        val draft: WeChatDraft,
    ) : PhoneTaskReview

    data class Tap(
        val action: VisionAction.Tap,
        val preview: Bitmap,
    ) : PhoneTaskReview
}

class PhoneTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

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
        val goal = intent?.getStringExtra(GOAL)?.trim()
        if (goal.isNullOrEmpty() || goal.length > MAX_GOAL_LENGTH) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.phone_task_title), NotificationManager.IMPORTANCE_LOW),
        )
        update(PhoneTaskStatus(R.string.phone_task_running, running = true))
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
                    val result =
                        withTimeoutOrNull(600_000) {
                            when (phoneTaskKind(goal)) {
                                PhoneTaskKind.WECHAT_MESSAGE -> {
                                    prepareDraft(goal, client, config)
                                }

                                PhoneTaskKind.WECHAT_NAVIGATION -> {
                                    runVision(goal, client, config)
                                }

                                PhoneTaskKind.SETTINGS -> {
                                    delay(850)
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
                                                onStep = ::showStep,
                                                afterAction = { delay(650) },
                                            )
                                        }.toStatus()
                                }
                            }
                        }
                    update(result ?: PhoneTaskStatus(R.string.phone_task_limit))
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

    private suspend fun prepareDraft(
        goal: String,
        client: ChatClient,
        config: ModelConfig,
    ): PhoneTaskStatus {
        showStep(1)
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
        val draft = WeChatTask.parseDraft(reply) ?: return PhoneTaskStatus(R.string.phone_task_draft_missing)
        return PhoneTaskStatus(R.string.phone_task_draft_ready, review = PhoneTaskReview.Draft(draft))
    }

    private suspend fun runVision(
        goal: String,
        client: ChatClient,
        config: ModelConfig,
    ): PhoneTaskStatus {
        delay(900)
        var feedback = ""
        var previousImage = ""
        var unchanged = 0
        repeat(40) { index ->
            val frame = ShizukuConnection.withScreenService { it.captureScreen() }
            if (frame.packageName != WECHAT_PACKAGE) return PhoneTaskStatus(R.string.phone_task_takeover)
            val image = frame.image?.softwareImage() ?: return PhoneTaskStatus(R.string.phone_task_screen)
            val fingerprint = withContext(Dispatchers.Default) { image.fingerprint() }
            if (fingerprint.isEmpty()) return PhoneTaskStatus(R.string.phone_task_takeover)
            unchanged = if (fingerprint == previousImage) unchanged + 1 else 0
            if (unchanged >= 3) return PhoneTaskStatus(R.string.phone_task_stalled)
            previousImage = fingerprint
            showStep(index + 1)
            val imageUrl =
                withContext(Dispatchers.Default) {
                    val bytes = ByteArrayOutputStream()
                    check(image.compress(Bitmap.CompressFormat.JPEG, 80, bytes))
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
            val action =
                try {
                    decideVision(client, config, goal, feedback, imageUrl)
                } catch (error: ChatException) {
                    if (error.status !in setOf(400, 413, 415, 422)) throw error
                    return PhoneTaskStatus(R.string.phone_task_vision_error)
                } ?: return PhoneTaskStatus(R.string.phone_task_reply)
            when (action) {
                is VisionAction.Finish -> {
                    return TaskResult.Finished(action.success, action.summary).toStatus()
                }

                VisionAction.TakeOver -> {
                    return PhoneTaskStatus(R.string.phone_task_takeover)
                }

                is VisionAction.Tap -> {
                    if (WeChatTask.needsManualAction(action.label)) return PhoneTaskStatus(R.string.phone_task_takeover)
                    val answer = CompletableDeferred<Boolean>()
                    pendingTap = answer
                    show(PhoneTaskStatus(R.string.phone_task_review_tap, running = true, review = PhoneTaskReview.Tap(action, image)))
                    if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                        Toast.makeText(this, R.string.phone_task_return_to_enco, Toast.LENGTH_LONG).show()
                    }
                    val allowed =
                        try {
                            answer.await()
                        } finally {
                            pendingTap = null
                        }
                    if (!allowed) return PhoneTaskStatus(R.string.phone_task_stopped)
                    showStep(index + 1)
                    delay(950)
                    val current = ShizukuConnection.withScreenService { it.captureScreen() }
                    val currentImage = current.image?.softwareImage()
                    val stillCurrent =
                        withContext(Dispatchers.Default) {
                            currentImage != null &&
                                currentImage.fingerprint() == fingerprint &&
                                image.sameTapArea(currentImage, action)
                        }
                    if (current.packageName != frame.packageName || !stillCurrent) {
                        feedback = "确认期间画面已改变，没有点按。"
                        delay(650)
                        return@repeat
                    }
                    val applied = ShizukuConnection.withScreenService { it.tap(current.id, action.x, action.y) }
                    feedback = if (applied) "已点按所选位置，检查新画面。" else "点按未执行，请重新观察。"
                }

                is VisionAction.Scroll -> {
                    val from = if (action.up) 730 else 340
                    val to = if (action.up) 340 else 730
                    val current = ShizukuConnection.withScreenService { it.captureScreen() }
                    val currentImage = current.image?.softwareImage()
                    val stillCurrent = withContext(Dispatchers.Default) { currentImage?.fingerprint() == fingerprint }
                    val applied =
                        current.packageName == WECHAT_PACKAGE && stillCurrent &&
                            ShizukuConnection.withScreenService { it.swipe(current.id, 500, from, 500, to) }
                    feedback = if (applied) "已滑动，检查新画面。" else "滑动未执行，请重新观察。"
                }
            }
            delay(650)
        }
        return PhoneTaskStatus(R.string.phone_task_limit)
    }

    private suspend fun decideVision(
        client: ChatClient,
        config: ModelConfig,
        goal: String,
        feedback: String,
        image: String,
    ): VisionAction? {
        var note = feedback
        repeat(3) {
            val reply = collectReply(client.streamImage(config, WeChatTask.visionPrompt, "goal: $goal\n上一步: $note", image))
            WeChatTask.parseVision(reply)?.let { return it }
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

    private fun showStep(step: Int) = show(PhoneTaskStatus(R.string.phone_task_step, step, running = true))

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
        if (status.value.running) update(PhoneTaskStatus(R.string.phone_task_stopped))
        stopSelf()
    }

    override fun onDestroy() {
        if (status.value.running) update(PhoneTaskStatus(R.string.phone_task_stopped))
        scope.cancel()
        super.onDestroy()
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
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.phone_task_title))
            .setContentText(if (state.step > 0) getString(state.message, state.step) else getString(state.message))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.phone_task_stop), stop).build())
            .build()
    }

    companion object {
        internal const val GOAL = "goal"
        internal const val MAX_GOAL_LENGTH = 500
        internal const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val STOP = "xyz.her3.enco.action.STOP_PHONE_TASK"
        private const val CHANNEL = "phone_task"
        private const val NOTIFICATION = 2
        private val mutableStatus = MutableStateFlow(PhoneTaskStatus(R.string.phone_task_idle))
        internal val status = mutableStatus.asStateFlow()
        private var pendingTap: CompletableDeferred<Boolean>? = null

        internal fun answerTap(allow: Boolean) {
            pendingTap?.complete(allow)
        }

        internal fun dismissDraft() {
            if (status.value.review is PhoneTaskReview.Draft) update(PhoneTaskStatus(R.string.phone_task_idle))
        }

        private fun update(state: PhoneTaskStatus) {
            mutableStatus.value = state
        }
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
