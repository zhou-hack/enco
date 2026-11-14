package xyz.her3.enco.device.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import xyz.her3.enco.R
import xyz.her3.enco.core.agent.PhoneOperator
import xyz.her3.enco.core.agent.TaskResult
import xyz.her3.enco.device.shizuku.SettingsDevice
import xyz.her3.enco.device.shizuku.ShizukuConnection
import xyz.her3.enco.llm.ChatClient
import xyz.her3.enco.llm.ChatMessage
import xyz.her3.enco.llm.ModelSettingsStore
import xyz.her3.enco.ui.MainActivity

internal data class PhoneTaskStatus(
    val message: Int,
    val step: Int = 0,
    val detail: String = "",
    val running: Boolean = false,
)

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
        val goal = intent?.getStringExtra(GOAL)?.take(500)?.trim()
        if (goal.isNullOrEmpty()) {
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
                        withTimeoutOrNull(120_000) {
                            delay(850)
                            ShizukuConnection.withSettingsDevice { device ->
                                PhoneOperator(device).run(goal, decide = { system, input ->
                                    val reply = StringBuilder()
                                    client.stream(config, listOf(ChatMessage("system", system), ChatMessage("user", input))).collect {
                                        check(reply.length + it.length <= 8192) { "动作回复超出长度限制" }
                                        reply.append(it)
                                    }
                                    reply.toString()
                                }, onStep = { step ->
                                    val next = PhoneTaskStatus(R.string.phone_task_step, step, running = true)
                                    update(next)
                                    manager.notify(NOTIFICATION, notification(next))
                                }, afterAction = { delay(650) })
                            }
                        }
                    update(result.toStatus())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    update(PhoneTaskStatus(R.string.phone_task_error))
                } finally {
                    stopSelf()
                }
            }
        return START_NOT_STICKY
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
        private const val STOP = "xyz.her3.enco.action.STOP_PHONE_TASK"
        private const val CHANNEL = "phone_task"
        private const val NOTIFICATION = 2
        private val mutableStatus = MutableStateFlow(PhoneTaskStatus(R.string.phone_task_idle))
        internal val status = mutableStatus.asStateFlow()

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
