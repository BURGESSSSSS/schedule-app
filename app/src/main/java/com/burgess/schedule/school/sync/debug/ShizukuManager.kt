package com.burgess.schedule.school.sync.debug

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import com.burgess.schedule.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val component = ComponentName(context.packageName, ShizukuUserService::class.java.name)
    private val serviceArgs = Shizuku.UserServiceArgs(
        component
    ).processNameSuffix("school_sync_probe").daemon(false).tag("school_webview_probe").version(1)
    private val _diagnostics = MutableStateFlow(
        BindingDiagnostics(
            applicationId = context.packageName,
            namespace = BuildConfig::class.java.name.substringBeforeLast('.'),
            serviceClass = ShizukuUserService::class.java.name,
            componentName = component.flattenToString()
        )
    )
    val diagnostics = _diagnostics.asStateFlow()

    private fun updatePending(change: (BindingDiagnostics) -> BindingDiagnostics) {
        _diagnostics.update { current ->
            if (current.stage in setOf(
                    BindingStage.UID_VERIFIED,
                    BindingStage.SERVICE_DISCONNECTED,
                    BindingStage.BIND_TIMEOUT,
                    BindingStage.BIND_EXCEPTION
                )) current else change(current)
        }
    }

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
        _diagnostics.update { it.copy(shizukuBinderAlive = running, permissionGranted = authorized) }
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
        _diagnostics.update {
            it.copy(
                bindRequested = true,
                serviceConnected = false,
                binderValid = null,
                uidQuerySucceeded = false,
                uid = null,
                stage = BindingStage.BIND_REQUESTED,
                exceptionType = null,
                exceptionMessage = null,
                released = false
            )
        }
        var connection: ServiceConnection? = null
        try {
            val service = try { withTimeout(20_000) {
                suspendCancellableCoroutine<IUserService> { continuation ->
                    fun fail(type: String, message: String, error: ProbeError = ProbeError.BIND_FAILED) {
                        if (!continuation.isActive) return
                        updatePending { it.copy(stage = BindingStage.BIND_EXCEPTION, exceptionType = type, exceptionMessage = safeDiagnosticMessage(message)) }
                        continuation.resumeWithException(ProbeException(error))
                    }
                    val connected = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                            if (!continuation.isActive) return
                            updatePending { it.copy(stage = BindingStage.SERVICE_CONNECTED, serviceConnected = true) }
                            if (binder == null) {
                                updatePending { it.copy(binderValid = false) }
                                fail("NullBinder", "onServiceConnected returned a null binder")
                                return
                            }
                            try {
                                val alive = binder.isBinderAlive
                                updatePending { it.copy(binderValid = alive) }
                                if (!alive) {
                                    fail("DeadBinder", "onServiceConnected returned an inactive binder")
                                    return
                                }
                                val remote = IUserService.Stub.asInterface(binder)
                                if (remote == null) {
                                    fail("InterfaceUnavailable", "IUserService.Stub.asInterface returned null")
                                    return
                                }
                                updatePending { it.copy(stage = BindingStage.BINDER_READY) }
                                if (continuation.isActive) continuation.resume(remote)
                            } catch (e: Exception) {
                                fail(e.javaClass.simpleName, e.message ?: "Binder interface conversion failed")
                            }
                        }
                        override fun onServiceDisconnected(name: ComponentName) {
                            if (!continuation.isActive) return
                            updatePending { it.copy(stage = BindingStage.SERVICE_DISCONNECTED) }
                            continuation.resumeWithException(ProbeException(ProbeError.BIND_FAILED))
                        }
                        override fun onBindingDied(name: ComponentName) {
                            fail("BindingDied", "Service binding died")
                        }
                        override fun onNullBinding(name: ComponentName) {
                            fail("NullBinding", "Service returned a null binding")
                        }
                    }
                    connection = connected
                    try { Shizuku.bindUserService(serviceArgs, connected) }
                    catch (e: Exception) {
                        if (continuation.isActive) {
                            updatePending { it.copy(stage = BindingStage.BIND_EXCEPTION, exceptionType = e.javaClass.simpleName, exceptionMessage = safeDiagnosticMessage(e.message)) }
                            continuation.resumeWithException(ProbeException(ProbeError.BIND_FAILED))
                        }
                    }
                }
            } } catch (_: TimeoutCancellationException) {
                _diagnostics.update { it.copy(stage = BindingStage.BIND_TIMEOUT, exceptionType = "TimeoutCancellationException", exceptionMessage = "No service connection callback within 20 seconds") }
                throw ProbeException(ProbeError.BIND_FAILED)
            }
            val uid = try { withContext(Dispatchers.IO) { service.uid } }
            catch (e: Exception) {
                _diagnostics.update { it.copy(stage = BindingStage.BIND_EXCEPTION, exceptionType = e.javaClass.simpleName, exceptionMessage = safeDiagnosticMessage(e.message)) }
                throw ProbeException(ProbeError.BIND_FAILED)
            }
            _diagnostics.update { it.afterUid(uid) }
            if (uid != Process.SHELL_UID) throw ProbeException(ProbeError.NOT_SHELL)
            return block(service)
        } finally {
            connection?.let {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        Shizuku.unbindUserService(serviceArgs, it, true)
                        _diagnostics.update { snapshot -> snapshot.copy(released = true) }
                    } catch (e: Exception) {
                        _diagnostics.update { snapshot ->
                            if (snapshot.exceptionType != null) snapshot
                            else snapshot.copy(exceptionType = e.javaClass.simpleName, exceptionMessage = safeDiagnosticMessage(e.message))
                        }
                    }
                }
            }
        }
    }

    suspend fun verifyUserService() = withShellService { /* getUid is the only remote call above */ }
}
