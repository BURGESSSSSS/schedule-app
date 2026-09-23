package com.burgess.schedule.school.sync.debug

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom

internal data class CdpPage(val title: String, val safeUrl: String, val type: String, val schoolMatch: Boolean)
internal data class ScanResult(
    val uid: Int,
    val socketCount: Int,
    val bridgeConnected: Boolean,
    val pages: List<CdpPage>,
    val errors: List<ProbeError>
)

internal object CdpProbe {
    private const val SCHOOL_HOST = "testjwgl.sdbi.edu.cn"

    suspend fun scan(manager: ShizukuManager): ScanResult = manager.withShellService { service ->
        withContext(Dispatchers.IO) {
            val uid = try { service.uid } catch (_: Exception) { throw ProbeException(ProbeError.BIND_FAILED) }
            if (uid != Process.SHELL_UID) throw ProbeException(ProbeError.NOT_SHELL)
            val sockets = try { service.findWebViewSockets().orEmpty().distinct() }
            catch (_: Exception) { throw ProbeException(ProbeError.SOCKET_SCAN_FAILED) }
            val pages = mutableListOf<CdpPage>()
            val errors = mutableSetOf<ProbeError>()
            var connected = false
            for (socket in sockets) {
                var bridgeStarted = false
                try {
                    val token = randomToken()
                    val port = service.startBridge(socket, token)
                    bridgeStarted = true
                    try {
                        val json = readList(port, token)
                        connected = true
                        pages += parsePages(json)
                    } finally {
                        try { service.stopBridge() } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                    errors += if (bridgeStarted) ProbeError.CDP_FAILED else ProbeError.BRIDGE_FAILED
                }
            }
            ScanResult(uid, sockets.size, connected, pages.distinctBy { it.type to it.safeUrl }, errors.toList())
        }
    }

    private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun readList(port: Int, token: String): String {
        require(port in 1..65535)
        val connection = (URL("http://127.0.0.1:$port/json/list").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Probe-Token", token)
            connection.connectTimeout = 3_000
            connection.readTimeout = 4_000
            if (connection.responseCode != 200) throw ProbeException(ProbeError.CDP_FAILED)
            connection.inputStream.use { stream ->
                val data = ByteArrayOutputStream()
                val chunk = ByteArray(8_192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (data.size() + count > 512 * 1_024) throw ProbeException(ProbeError.CDP_FAILED)
                    data.write(chunk, 0, count)
                }
                return data.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePages(json: String): List<CdpPage> {
        val targets = JSONArray(json)
        return (0 until minOf(targets.length(), 256)).mapNotNull { index ->
            val target = targets.optJSONObject(index) ?: return@mapNotNull null
            val rawUrl = target.optString("url")
            val safeUrl = safePageUrl(rawUrl) ?: return@mapNotNull null
            val host = try { URI(rawUrl).host?.lowercase() } catch (_: Exception) { null }
            CdpPage(
                title = target.optString("title").take(120),
                safeUrl = safeUrl,
                type = target.optString("type").take(30),
                schoolMatch = host == SCHOOL_HOST || host?.endsWith(".$SCHOOL_HOST") == true
            )
        }
    }
}

/** Keep only scheme, host, optional port and path; never retain query, fragment or user info. */
internal fun safePageUrl(raw: String): String? = try {
    val uri = URI(raw)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    if (scheme !in setOf("http", "https") || host.isNullOrBlank()) null
    else "$scheme://$host${if (uri.port >= 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty().ifEmpty { "/" }}"
} catch (_: Exception) { null }
