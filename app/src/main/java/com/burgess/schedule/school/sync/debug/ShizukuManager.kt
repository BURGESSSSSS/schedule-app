package com.burgess.schedule.school.sync.debug

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class ProbeError {
    NOT_INSTALLED, NOT_RUNNING, PERMISSION_DENIED, PERMISSION_TIMEOUT, UNSUPPORTED_VERSION,
    BIND_FAILED, NOT_SHELL, SOCKET_SCAN_FAILED, BRIDGE_FAILED, CDP_FAILED, UNEXPECTED
}

internal class ProbeException(val kind: ProbeError) : Exception(kind.name)

internal data class ShizukuState(val installed: Boolean, val running: Boolean, val authorized: Boolean)

internal class ShizukuManager(private val context: Context) {
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    ).daemon(false).tag("school_webview_probe").version(1)

    fun check(): ShizukuState {
        val installed = try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        val authorized = if (running) {
            try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            catch (_: Exception) { false }
        } else false
        return ShizukuState(installed, running, authorized)
    }

    suspend fun requestPermission() {
        val state = withContext(Dispatchers.IO) { check() }
        if (!state.installed) throw ProbeException(ProbeError.NOT_INSTALLED)
        if (!state.running) throw ProbeException(ProbeError.NOT_RUNNING)
        if (state.authorized) return
        if (Shizuku.isPreV11()) throw ProbeException(ProbeError.UNSUPPORTED_VERSION)
        if (Shizuku.shouldShowRequestPermissionRationale()) throw ProbeException(ProbeError.PERMISSION_DENIED)
        try { withTimeout(30_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val requestCode = 7107
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                    if (code == requestCode && continuation.isActive) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (result == PackageManager.PERMISSION_GRANTED) continuation.resume(Unit)
                        else continuation.resumeWithException(ProbeException(ProbeError.PERMISSION_DENIED))
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                try { Shizuku.requestPermission(requestCode) }
                catch (_: Exception) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (continuation.isActive) continuation.resumeWithException(ProbeException(ProbeError.NOT_RUNNING))
                }
            }
        } } catch (_: TimeoutCancellationException) {
            throw ProbeException(ProbeError.PERMISSION_TIMEOUT)
        }
    }

    suspend fun <T> withShellService(block: suspend (IUserService) -> T): T {
        val state = withContext(Dispatchers.IO) { check() }
        if (!state.installed) throw ProbeException(ProbeError.NOT_INSTALLED)
        if (!state.running) throw ProbeException(ProbeError.NOT_RUNNING)
        if (!state.authorized) throw ProbeException(ProbeError.PERMISSION_DENIED)
        if (Shizuku.isPreV11()) throw ProbeException(ProbeError.UNSUPPORTED_VERSION)
        var connection: ServiceConnection? = null
        try {
            val service = try { withTimeout(10_000) {
                suspendCancellableCoroutine<IUserService> { continuation ->
                    val connected = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            if (continuation.isActive) continuation.resume(IUserService.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (continuation.isActive) continuation.resumeWithException(ProbeException(ProbeError.BIND_FAILED))
                        }
                    }
                    connection = connected
                    try { Shizuku.bindUserService(serviceArgs, connected) }
                    catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(ProbeException(ProbeError.BIND_FAILED))
                    }
                }
            } } catch (_: TimeoutCancellationException) {
                throw ProbeException(ProbeError.BIND_FAILED)
            }
            val uid = withContext(Dispatchers.IO) { service.uid }
            if (uid != Process.SHELL_UID) throw ProbeException(ProbeError.NOT_SHELL)
            return block(service)
        } finally {
            connection?.let {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { Shizuku.unbindUserService(serviceArgs, it, true) } catch (_: Exception) { }
                }
            }
        }
    }
}
