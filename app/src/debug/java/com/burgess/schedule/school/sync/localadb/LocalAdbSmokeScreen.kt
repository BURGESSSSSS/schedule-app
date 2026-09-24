package com.burgess.schedule.school.sync.localadb

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ADB_CONNECTION_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_EXECUTED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.ID_RUNNING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.NOT_RUN
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.PAIRING_SUCCESS
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_FAILED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_OPENED
import com.burgess.schedule.school.sync.localadb.LocalAdbCheckState.SHELL_OPENING
import com.burgess.schedule.ui.components.AppCard
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.SectionLabel
import com.burgess.schedule.ui.components.SubPageScaffold
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LocalAdbCheckState {
    NOT_RUN,
    PAIRING,
    PAIRING_SUCCESS,
    PAIRING_FAILED,
    ADB_CONNECTING,
    ADB_CONNECTED,
    ADB_CONNECTION_FAILED,
    SHELL_OPENING,
    SHELL_OPENED,
    SHELL_FAILED,
    ID_RUNNING,
    ID_EXECUTED,
    ID_FAILED
}

internal fun isShellUid2000(output: String): Boolean =
    Regex("(?:^|\\s)uid=2000\\(shell\\)(?:\\s|$)").containsMatchIn(output.trim())

private fun actualUidLabel(output: String): String {
    val match = Regex("(?:^|\\s)uid=(\\d+)\\(([^)]*)\\)").find(output.trim())
    return match?.let { "${it.groupValues[1]} (${it.groupValues[2]})" } ?: "未识别"
}

private fun safeFailure(error: Exception, secret: String? = null): String {
    var message = error.message.orEmpty()
    if (!secret.isNullOrEmpty()) message = message.replace(secret, "[已隐藏]")
    message = message.replace(Regex("\\b\\d{6}\\b"), "[已隐藏]").take(180)
    val type = error.javaClass.simpleName.ifBlank { "Exception" }
    return if (message.isBlank()) type else "$type：$message"
}

@Composable
internal fun LocalAdbSmokeScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val session = remember(context) { LocalAdbSmokeSession(context) }
    val operationScope = rememberCoroutineScope()
    val colors = LocalAppColors.current

    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pairingState by remember { mutableStateOf(NOT_RUN) }
    var connectionState by remember { mutableStateOf(NOT_RUN) }
    var shellState by remember { mutableStateOf(NOT_RUN) }
    var idState by remember { mutableStateOf(NOT_RUN) }
    var idOutput by remember { mutableStateOf("") }
    var uidState by remember { mutableStateOf("未验证") }
    var lastError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(session) {
        onDispose {
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                try {
                    session.close()
                } catch (_: Exception) {
                    // The one-shot PoC has no background connection after the page is closed.
                } finally {
                    cleanupScope.cancel()
                }
            }
        }
    }

    fun pairWithWirelessDebuggingCode() {
        if (busy) return
        val port = pairingPort.toIntOrNull()
        val submittedCode = pairingCode
        if (port == null || port !in 1..65535 || submittedCode.length != 6 || submittedCode.any { !it.isDigit() }) {
            lastError = "请输入有效配对端口和 6 位配对码。"
            pairingState = PAIRING_FAILED
            return
        }

        pairingCode = ""
        lastError = null
        pairingState = PAIRING
        connectionState = NOT_RUN
        shellState = NOT_RUN
        idState = NOT_RUN
        idOutput = ""
        uidState = "未验证"
        busy = true
        operationScope.launch {
            try {
                val paired = withContext(Dispatchers.IO) {
                    session.pair(port, submittedCode)
                }
                if (paired) {
                    pairingState = PAIRING_SUCCESS
                } else {
                    pairingState = PAIRING_FAILED
                    lastError = "配对没有完成。请确认端口和配对码来自本机当前的 Wireless Debugging 配对窗口。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                pairingState = PAIRING_FAILED
                lastError = safeFailure(exception, submittedCode)
            } finally {
                busy = false
            }
        }
    }

    fun connectAndRunId() {
        if (busy) return
        val port = connectPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            lastError = "请输入 Wireless Debugging 主页面显示的有效连接端口。"
            connectionState = ADB_CONNECTION_FAILED
            return
        }

        lastError = null
        connectionState = ADB_CONNECTING
        shellState = NOT_RUN
        idState = NOT_RUN
        idOutput = ""
        uidState = "未验证"
        busy = true
        operationScope.launch {
            try {
                val connected = withContext(Dispatchers.IO) {
                    session.connect(port)
                }
                if (!connected) throw IOException("TLS connection was not established")
                connectionState = ADB_CONNECTED

                shellState = SHELL_OPENING
                idState = ID_RUNNING
                val stream = withContext(Dispatchers.IO) {
                    session.openIdShell()
                }
                shellState = SHELL_OPENED

                val output = withContext(Dispatchers.IO) {
                    session.readIdOutput(stream)
                }.trim()
                if (output.isBlank()) {
                    idState = ID_FAILED
                    uidState = "UID_2000_NOT_VERIFIED（id 没有输出）"
                    lastError = "固定命令 id 没有返回输出。"
                } else {
                    idOutput = output
                    idState = ID_EXECUTED
                    uidState = if (isShellUid2000(output)) {
                        "UID_2000_VERIFIED"
                    } else {
                        "UID_2000_NOT_VERIFIED（实际 UID：${actualUidLabel(output)}）"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                when (connectionState) {
                    ADB_CONNECTING -> connectionState = ADB_CONNECTION_FAILED
                    ADB_CONNECTED -> if (shellState == SHELL_OPENING) shellState = SHELL_FAILED
                    else -> Unit
                }
                if (idState == ID_RUNNING) idState = ID_FAILED
                lastError = safeFailure(exception)
            } finally {
                busy = false
            }
        }
    }

    SubPageScaffold("Local ADB 最小验证", onBack) {
        Text(
            "只连接本机 127.0.0.1。你需要在系统 Wireless Debugging 页面手动配对；本页不会打开或更改系统设置。",
            style = AppType.body,
            color = colors.mut
        )

        SectionLabel("步骤 1 · 配对")
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                "在系统设置的 Wireless Debugging 页面选择“使用配对码配对”，将系统显示的配对端口和 6 位配对码填入这里。",
                style = AppType.body,
                color = colors.ink
            )
            OutlinedTextField(
                value = pairingPort,
                onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("配对端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6 位配对码（仅本次使用）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            PillButton(
                if (busy && pairingState == PAIRING) "配对中…" else "配对本机 Wireless Debugging",
                onClick = ::pairWithWirelessDebuggingCode,
                style = PillStyle.Primary
            )
            Text("配对状态：${pairingState.name}", style = AppType.caption, color = colors.mut)
        }

        SectionLabel("步骤 2 · TLS 连接并执行唯一命令")
        AppCard(Modifier.fillMaxWidth()) {
            Text("回到 Wireless Debugging 主页面，填写当前显示的连接端口。连接成功后本页只运行 id。", style = AppType.body, color = colors.ink)
            OutlinedTextField(
                value = connectPort,
                onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wireless Debugging 连接端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            PillButton(
                if (busy && connectionState == ADB_CONNECTING) "连接并执行中…" else "连接并只运行 id",
                onClick = ::connectAndRunId,
                style = PillStyle.Primary
            )
            Text("ADB 状态：${connectionState.name}", style = AppType.caption, color = colors.mut)
            Text("Shell 状态：${shellState.name}", style = AppType.caption, color = colors.mut)
            Text("命令状态：${idState.name}", style = AppType.caption, color = colors.mut)
            Text("UID 校验：$uidState", style = AppType.caption, color = colors.ink)
            if (idOutput.isNotBlank()) {
                SectionLabel("id 输出")
                Text(idOutput, style = AppType.caption, color = colors.ink)
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                Text("密钥只保存在 App 私有目录。配对码不会保存或写入日志。", style = AppType.caption, color = colors.mut)
                if (lastError != null) Text("错误：$lastError", style = AppType.caption, color = colors.danger)
            }
        }
    }
}

private class LocalAdbSmokeSession(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var connectionManager: LocalAdbConnectionManager? = null

    @Synchronized
    private fun manager(): LocalAdbConnectionManager = connectionManager
        ?: LocalAdbConnectionManager(appContext).also { connectionManager = it }

    fun pair(port: Int, pairingCode: String): Boolean =
        manager().pair(LOOPBACK, port, pairingCode)

    fun connect(port: Int): Boolean {
        val manager = manager()
        if (manager.isConnected) return true
        return manager.connect(LOOPBACK, port)
    }

    fun openIdShell(): AdbStream = manager().openStream("shell:id")

    fun readIdOutput(stream: AdbStream): String {
        try {
            return stream.openInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                while (output.length < MAX_ID_OUTPUT_CHARS) {
                    val line = reader.readLine() ?: break
                    if (output.isNotEmpty()) output.append('\n')
                    output.append(line.take(MAX_ID_OUTPUT_CHARS - output.length))
                }
                output.toString()
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    @Synchronized
    override fun close() {
        connectionManager?.close()
        connectionManager = null
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_ID_OUTPUT_CHARS = 1024
    }
}

private class LocalAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {
    private val identity = LocalAdbIdentity.loadOrCreate(context.applicationContext)

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = "ScheduleLocalAdbPoC"
}

private data class LocalAdbIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
) {
    companion object {
        private const val IDENTITY_FILE = "adb-identity.bin"
        private const val MAX_KEY_BYTES = 16 * 1024
        private const val MAX_CERT_BYTES = 64 * 1024

        fun loadOrCreate(context: Context): LocalAdbIdentity {
            val directory = File(context.filesDir, "local-adb")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create App-private Local ADB directory")
            }
            val file = File(directory, IDENTITY_FILE)
            if (file.exists()) return read(file)

            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()
            val certificate = createCertificate(keyPair)
            val payload = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeInt(keyPair.private.encoded.size)
                    data.write(keyPair.private.encoded)
                    data.writeInt(certificate.encoded.size)
                    data.write(certificate.encoded)
                }
                bytes.toByteArray()
            }
            writeAtomically(file, payload)
            return LocalAdbIdentity(keyPair.private, certificate)
        }

        private fun read(file: File): LocalAdbIdentity {
            val input = DataInputStream(ByteArrayInputStream(AtomicFile(file).openRead().use { it.readBytes() }))
            input.use { data ->
                val keySize = data.readInt()
                require(keySize in 1..MAX_KEY_BYTES) { "Stored Local ADB key is invalid" }
                val keyBytes = ByteArray(keySize).also { data.readFully(it) }
                val certificateSize = data.readInt()
                require(certificateSize in 1..MAX_CERT_BYTES) { "Stored Local ADB certificate is invalid" }
                val certificateBytes = ByteArray(certificateSize).also { data.readFully(it) }
                require(data.available() == 0) { "Stored Local ADB identity has unexpected data" }
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
                return LocalAdbIdentity(privateKey, certificate)
            }
        }

        private fun writeAtomically(file: File, payload: ByteArray) {
            val atomicFile = AtomicFile(file)
            var output: FileOutputStream? = null
            try {
                output = atomicFile.startWrite()
                output.write(payload)
                output.fd.sync()
                atomicFile.finishWrite(output)
                output = null
            } catch (exception: Exception) {
                output?.let(atomicFile::failWrite)
                throw exception
            }
        }

        private fun createCertificate(keyPair: KeyPair): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - TimeUnit.MINUTES.toMillis(1))
            val notAfter = Date(now + TimeUnit.DAYS.toMillis(3650))
            val algorithmName = "SHA512withRSA"
            val subject = X500Name("CN=ScheduleLocalAdbPoC")
            val extensions = CertificateExtensions().apply {
                set(
                    "SubjectKeyIdentifier",
                    SubjectKeyIdentifierExtension(KeyIdentifier(keyPair.public).identifier)
                )
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val certificateInfo = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber((SecureRandom().nextInt() and Int.MAX_VALUE).coerceAtLeast(1)))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithmName)))
                set("subject", CertificateSubjectName(subject))
                set("key", CertificateX509Key(keyPair.public))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(subject))
                set("extensions", extensions)
            }
            return X509CertImpl(certificateInfo).apply { sign(keyPair.private, algorithmName) }
        }
    }
}
