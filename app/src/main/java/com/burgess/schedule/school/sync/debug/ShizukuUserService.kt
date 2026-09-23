package com.burgess.schedule.school.sync.debug

import android.os.Process
import android.util.Log
import java.io.File

/** Runs in Shizuku's shell process. It exposes only socket discovery and a one-shot read-only list bridge. */
class ShizukuUserService : IUserService.Stub() {
    companion object {
        private const val LOG_TAG = "SchoolUserService"

        init {
            Log.i(LOG_TAG, "CLASS_INITIALIZED pid=${Process.myPid()} uid=${Process.myUid()}")
        }
    }

    private val bridgeLock = Any()
    private var bridge: DevtoolsBridge? = null

    init {
        // This runs after the generated Binder Stub constructor. Earlier failures need Shizuku's own Logcat.
        Log.i(LOG_TAG, "CONSTRUCTOR_ENTERED pid=${Process.myPid()} uid=${Process.myUid()}")
        Log.i(LOG_TAG, "BINDER_STUB_READY alive=${asBinder().isBinderAlive}")
        Log.i(LOG_TAG, "CONSTRUCTOR_COMPLETED")
    }

    override fun getUid(): Int = Process.myUid().also {
        Log.i(LOG_TAG, "GET_UID uid=$it pid=${Process.myPid()}")
    }

    override fun findWebViewSockets(): Array<String> {
        val names = linkedSetOf<String>()
        try {
            File("/proc/net/unix").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val path = line.substringAfterLast(' ', "").removePrefix("@")
                    if (path.startsWith("webview_devtools_remote") &&
                        path.length <= 128 && path.all { it.isLetterOrDigit() || it == '_' || it == '-' }
                    ) names += path
                }
            }
        } catch (_: Exception) {
            // Binder caller maps this code to a typed, non-fatal scan error.
            throw IllegalStateException("SOCKET_SCAN_FAILED")
        }
        return names.take(64).toTypedArray()
    }

    override fun startBridge(socketName: String, token: String): Int {
        require(socketName in findWebViewSockets()) { "SOCKET_UNAVAILABLE" }
        require(token.length == 64 && token.all { it in "0123456789abcdef" }) { "INVALID_TOKEN" }
        synchronized(bridgeLock) {
            bridge?.close()
            return DevtoolsBridge(socketName, token).also { bridge = it }.port
        }
    }

    override fun stopBridge() {
        synchronized(bridgeLock) {
            bridge?.close()
            bridge = null
        }
    }

    override fun destroy() {
        stopBridge()
        System.exit(0)
    }
}
