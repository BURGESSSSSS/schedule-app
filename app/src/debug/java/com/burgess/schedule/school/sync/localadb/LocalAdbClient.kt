package com.burgess.schedule.school.sync.localadb

import android.content.Context
import android.os.Build
import android.util.AtomicFile
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MAX_ID_OUTPUT_CHARS = 1024
private const val MAX_SOCKET_SCAN_OUTPUT_BYTES = 32 * 1024

internal enum class LocalAdbReadOnlyCommand(
    internal val shellInput: String,
    internal val outputLimit: Int,
    internal val isComplete: (String) -> Boolean
) {
    ID("id\n", MAX_ID_OUTPUT_CHARS, ::hasCompleteUidField),
    DEVTOOLS_SOCKET_SCAN(
        "awk 'NR == 1 { next } { " +
            "name = \$8; " +
            "if (NF >= 8 && name != \"\") { " +
            "named++; " +
            "if (substr(name, 1, 1) == \"@\") abstract++; " +
            "if (tolower(name) ~ /devtools|xweb|webview|tbs/) " +
            "printf \"__DEVTOOLS_SOCKET__|%s\\n\", name; " +
            "} " +
            "} END { printf \"__PROC_UNIX_SUMMARY__|%d|%d|%d\\n\", NR, named, abstract }' " +
            "/proc/net/unix 2>/dev/null; " +
            "printf '__PROC_UNIX_EXIT__|%s\\n' \"\$?\"; " +
            "ps -A -o PID,NAME 2>/dev/null | awk '" +
            "NR > 0 && \$1 ~ /^[0-9]+\$/ && " +
            "(\$2 == \"com.tencent.wework\" || index(\$2, \"com.tencent.wework:\") == 1) { " +
            "printf \"__WXWORK_PROCESS__|%s|%s\\n\", \$1, \$2 " +
            "} END { if (NR > 0) print \"__WXWORK_QUERY_DONE__\" }'; " +
            "printf '__LOCAL_ADB_SCAN_DONE__\\n'\n",
        MAX_SOCKET_SCAN_OUTPUT_BYTES,
        ::hasLocalAdbScanFinished
    )
}

internal data class LocalAdbCommandOutput(
    val output: String,
    val issue: LocalAdbReadIssue,
    val failureSummary: String?
)

internal class LocalAdbClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var connectionManager: LocalAdbConnectionManager? = null

    @Synchronized
    private fun manager(): LocalAdbConnectionManager = connectionManager
        ?: LocalAdbConnectionManager(appContext).also { connectionManager = it }

    suspend fun pair(port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        manager().pair(LOOPBACK, port, pairingCode)
    }

    suspend fun connect(port: Int): Boolean = withContext(Dispatchers.IO) {
        val manager = manager()
        manager.isConnected || manager.connect(LOOPBACK, port)
    }

    suspend fun executeReadOnly(command: LocalAdbReadOnlyCommand): LocalAdbCommandOutput =
        withContext(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            var issue = LocalAdbReadIssue.NONE
            var failureSummary: String? = null
            var stream: AdbStream? = null
            try {
                stream = manager().openStream("shell:")
                val commandOutput = stream.openOutputStream()
                commandOutput.write(command.shellInput.toByteArray(Charsets.UTF_8))
                commandOutput.flush()
                val input = stream.openInputStream()
                withTimeout(COMMAND_READ_TIMEOUT_MS) {
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    while (output.size() < command.outputLimit) {
                        val currentOutput = output.toString(Charsets.UTF_8.name())
                        if (command.isComplete(currentOutput)) break

                        val available = input.available()
                        if (available <= 0) {
                            if (stream.isClosed) {
                                issue = LocalAdbReadIssue.STREAM_CLOSED
                                failureSummary = "IOException: Stream closed before command output was complete."
                                break
                            }
                            delay(READ_POLL_INTERVAL_MS)
                            continue
                        }
                        val count = input.read(
                            buffer,
                            0,
                            minOf(available, buffer.size, command.outputLimit - output.size())
                        )
                        if (count < 0) {
                            issue = LocalAdbReadIssue.END_OF_STREAM
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                    if (output.size() >= command.outputLimit &&
                        !command.isComplete(output.toString(Charsets.UTF_8.name()))
                    ) {
                        issue = LocalAdbReadIssue.IO_ERROR
                        failureSummary = "Output limit reached before command output was complete."
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                issue = LocalAdbReadIssue.TIMEOUT
                failureSummary = "Timeout: No complete command output arrived within 5 seconds."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                issue = if (exception.message.orEmpty().contains("stream closed", ignoreCase = true)) {
                    LocalAdbReadIssue.STREAM_CLOSED
                } else {
                    LocalAdbReadIssue.IO_ERROR
                }
                failureSummary = safeReadFailure(exception)
            } finally {
                runCatching { stream?.close() }
            }

            val resultOutput = output.toString(Charsets.UTF_8.name())
            LocalAdbCommandOutput(
                output = resultOutput,
                issue = issue,
                failureSummary = failureSummary
            )
        }

    @Synchronized
    override fun close() {
        connectionManager?.close()
        connectionManager = null
    }

    private fun safeReadFailure(error: IOException): String {
        val message = error.message.orEmpty().take(180)
        return if (message.isBlank()) {
            "${error.javaClass.simpleName}: Failed to read command output."
        } else {
            "${error.javaClass.simpleName}: $message"
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val COMMAND_READ_TIMEOUT_MS = 5_000L
        const val READ_POLL_INTERVAL_MS = 25L
        const val READ_BUFFER_SIZE = 512
    }
}

private fun hasLocalAdbScanFinished(output: String): Boolean =
    output.lineSequence().any { it.trim() == "__LOCAL_ADB_SCAN_DONE__" }

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
