package xyz.her3.enco.device.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import xyz.her3.enco.BuildConfig
import xyz.her3.enco.device.shell.EncoShellService
import xyz.her3.enco.device.shell.IEncoShellService

internal enum class ShizukuStatus {
    CHECKING,
    STOPPED,
    UPDATE_REQUIRED,
    NEED_PERMISSION,
    REQUESTING_PERMISSION,
    MANAGE_PERMISSION,
    CONNECTING,
    CONNECTED,
    CONNECTION_FAILED,
}

internal object ShizukuConnection {
    private const val LOG_TAG = "enco.shizuku"
    private const val PERMISSION_REQUEST = 1
    private const val MIN_SERVER_VERSION = 13
    private const val USER_SERVICE_VERSION = 14
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val args =
        Shizuku
            .UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, EncoShellService::class.java.name))
            .processNameSuffix("shell")
            .version(USER_SERVICE_VERSION)
            .daemon(false)
    private val mutableStatus = MutableStateFlow(ShizukuStatus.CHECKING)
    val status = mutableStatus.asStateFlow()

    private var requestedOnce = false
    private var refreshJob: Job? = null
    private var timeoutJob: Job? = null
    private var handshakeJob: Job? = null
    private var screenService: IEncoShellService? = null

    suspend fun <T> withScreenService(action: (IEncoShellService) -> T): T {
        val service = checkNotNull(screenService) { "Shizuku 助手进程未连接" }
        return withContext(Dispatchers.IO) { action(service) }
    }

    suspend fun <T> withSettingsDevice(action: suspend (SettingsDevice) -> T): T {
        val service = checkNotNull(screenService) { "Shizuku 助手进程未连接" }
        return action(SettingsDevice(service))
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                handshakeJob?.cancel()
                handshakeJob =
                    scope.launch {
                        try {
                            val service = IEncoShellService.Stub.asInterface(binder)
                            val uid =
                                withContext(Dispatchers.IO) {
                                    service.processUid
                                }
                            timeoutJob?.cancel()
                            screenService = service
                            Log.i(LOG_TAG, "助手进程已连接，UID=$uid")
                            mutableStatus.value = ShizukuStatus.CONNECTED
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: RemoteException) {
                            reportConnectionFailure(error)
                        } catch (error: RuntimeException) {
                            reportConnectionFailure(error)
                        }
                    }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                screenService = null
                handshakeJob?.cancel()
                timeoutJob?.cancel()
                mutableStatus.value =
                    if (Shizuku.pingBinder()) ShizukuStatus.CONNECTION_FAILED else ShizukuStatus.STOPPED
            }
        }

    init {
        Shizuku.addBinderDeadListener {
            screenService = null
            refreshJob?.cancel()
            handshakeJob?.cancel()
            timeoutJob?.cancel()
            mutableStatus.value = ShizukuStatus.STOPPED
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    scope.launch {
                        refreshJob?.join()
                        mutableStatus.value = ShizukuStatus.CHECKING
                        refresh()
                    }
                } else {
                    mutableStatus.value = ShizukuStatus.MANAGE_PERMISSION
                }
            }
        }
        Shizuku.addBinderReceivedListenerSticky { refresh() }
    }

    fun refresh(requestPermission: Boolean = false) {
        if (refreshJob?.isActive == true) return
        if (mutableStatus.value == ShizukuStatus.REQUESTING_PERMISSION) return
        refreshJob =
            scope.launch {
                try {
                    val current = withContext(Dispatchers.IO) { checkStatus() }
                    when {
                        current == ShizukuStatus.CONNECTING -> {
                            connect()
                        }

                        current == ShizukuStatus.NEED_PERMISSION && (requestPermission || !requestedOnce) -> {
                            requestedOnce = true
                            mutableStatus.value = ShizukuStatus.REQUESTING_PERMISSION
                            withContext(Dispatchers.IO) { Shizuku.requestPermission(PERMISSION_REQUEST) }
                        }

                        else -> {
                            screenService = null
                            mutableStatus.value = current
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    reportConnectionFailure(error)
                }
            }
    }

    private fun checkStatus(): ShizukuStatus =
        when {
            !Shizuku.pingBinder() -> ShizukuStatus.STOPPED
            Shizuku.isPreV11() || Shizuku.getVersion() < MIN_SERVER_VERSION -> ShizukuStatus.UPDATE_REQUIRED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.CONNECTING
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuStatus.MANAGE_PERMISSION
            else -> ShizukuStatus.NEED_PERMISSION
        }

    private suspend fun connect() {
        if (mutableStatus.value == ShizukuStatus.CONNECTED || timeoutJob?.isActive == true) return
        mutableStatus.value = ShizukuStatus.CONNECTING
        timeoutJob =
            scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                handshakeJob?.cancel()
                Log.w(LOG_TAG, "连接助手进程超时")
                mutableStatus.value = ShizukuStatus.CONNECTION_FAILED
            }
        withContext(Dispatchers.IO) { Shizuku.bindUserService(args, connection) }
    }

    private fun reportConnectionFailure(error: Exception) {
        screenService = null
        timeoutJob?.cancel()
        Log.w(LOG_TAG, "Shizuku 连接失败", error)
        mutableStatus.value =
            if (Shizuku.pingBinder()) ShizukuStatus.CONNECTION_FAILED else ShizukuStatus.STOPPED
    }
}
