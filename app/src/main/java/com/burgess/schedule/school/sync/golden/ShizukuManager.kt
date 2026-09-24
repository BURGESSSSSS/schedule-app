package com.burgess.schedule.school.sync.golden

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.burgess.schedule.BuildConfig
import rikka.shizuku.Shizuku

object ShizukuManager {
    private const val TAG = "GoldenShizukuManager"

    private var userService: IUserService? = null
    private var savedConnection: ServiceConnection? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(false)
        .version(22)

    fun bindUserService(
        onServiceConnected: () -> Unit,
        onPing: (String) -> Unit,
        onUid: (Int) -> Unit,
        onDisconnected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            onError("Shizuku 未运行")
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED }
                .getOrDefault(true)
        ) {
            onError("尚未获得 Shizuku 授权")
            return
        }
        userService?.let { service ->
            onServiceConnected()
            try {
                onPing(service.ping())
                onUid(service.getUid())
            } catch (exception: Exception) {
                clearFailedConnection(savedConnection)
                Log.e(TAG, "UserService smoke call failed", exception)
                onError(exception.javaClass.simpleName)
            }
            return
        }
        if (savedConnection != null) {
            onError("UserService 连接已发起，正在等待连接回调")
            return
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (savedConnection !== this) return
                val service = IUserService.Stub.asInterface(binder)
                if (service == null) {
                    Log.w(TAG, "UserService returned a null interface")
                    clearFailedConnection(this)
                    onError("UserService 返回空 Binder")
                    return
                }
                userService = service
                onServiceConnected()
                try {
                    onPing(service.ping())
                    onUid(service.getUid())
                } catch (exception: Exception) {
                    clearFailedConnection(this)
                    Log.e(TAG, "UserService smoke call failed", exception)
                    onError(exception.javaClass.simpleName)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (savedConnection !== this) return
                userService = null
                savedConnection = null
                onDisconnected()
            }
        }

        savedConnection = connection
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }
            .onFailure { exception ->
                Log.e(TAG, "bindUserService failed", exception)
                if (savedConnection === connection) savedConnection = null
                onError(exception.javaClass.simpleName)
            }
    }

    fun unbindUserService() {
        val connection = savedConnection
        savedConnection = null
        userService = null
        if (connection != null) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
                .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        }
    }

    private fun clearFailedConnection(connection: ServiceConnection?) {
        if (connection == null || savedConnection !== connection) return
        savedConnection = null
        userService = null
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            .onFailure { Log.w(TAG, "unbind failed UserService connection", it) }
    }
}
