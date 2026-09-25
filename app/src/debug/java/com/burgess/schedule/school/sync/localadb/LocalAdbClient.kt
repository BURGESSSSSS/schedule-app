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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

    /** Opens one explicitly selected abstract socket for the Debug-only DevTools discovery probe. */
    suspend fun openDevToolsSocket(socketName: String): AdbStream = withContext(Dispatchers.IO) {
        require(socketName.startsWith('@')) { "Expected an abstract Unix socket name" }
        val serviceName = socketName.removePrefix("@")
        require(serviceName.isNotBlank() && serviceName.none { it.isWhitespace() || it == ':' || it == '@' || it.isISOControl() }) {
            "Invalid abstract Unix socket name"
        }
        manager().openStream("localabstract:$serviceName")
    }

    suspend fun executeReadOnly(command: LocalAdbReadOnlyCommand): LocalAdbCommandOutput =
        withContext(Dispatchers.IO) {
            var issue = LocalAdbReadIssue.NONE
            var failureSummary: String? = null
            var output = ""
            var exitCode: Int? = null
            var commandCompleted = false
            var partialOutputReceived = false
            var stream: AdbStream? = null
            val wrappedCommand = wrapWithCommandFrame(command, newCommandFrameId())
            val wrapperEchoAllowance = wrappedCommand.input.toByteArray(Charsets.UTF_8).size
            val markerAllowance = wrappedCommand.beginMarker.length + wrappedCommand.doneMarker.length
            val buffer = CommandFrameBuffer(
                beginMarker = wrappedCommand.beginMarker,
                doneMarker = wrappedCommand.doneMarker,
                outputLimit = command.outputLimit + wrapperEchoAllowance + markerAllowance + FRAME_TERMINAL_OVERHEAD_BYTES,
                payloadLimit = command.outputLimit
            )
            try {
                withTimeout(command.timeoutMs) {
                    val activeStream = manager().openStream("shell:")
                    stream = activeStream
                    val commandOutput = activeStream.openOutputStream()
                    commandOutput.write(wrappedCommand.input.toByteArray(Charsets.UTF_8))
                    commandOutput.flush()
                    val input = activeStream.openInputStream()
                    when (val readResult = readUntilCommandFrame(
                        input = input,
                        isStreamClosed = { activeStream.isClosed },
                        buffer = buffer
                    )) {
                        is CommandFrameReadOutcome.Completed -> {
                            commandCompleted = true
                            output = readResult.payload.output
                            val commandExitCode = readResult.payload.exitCode
                            exitCode = commandExitCode
                            partialOutputReceived = buffer.hasOutput
                            if (commandExitCode != 0) {
                                issue = LocalAdbReadIssue.COMMAND_FAILED
                                failureSummary = "${command.safeLabel}: command exited with status $commandExitCode."
                            }
                        }
                        is CommandFrameReadOutcome.StreamClosed -> {
                            issue = LocalAdbReadIssue.STREAM_CLOSED
                            partialOutputReceived = readResult.partialOutputReceived
                            failureSummary = commandFailureSummary(command, "stream closed before sentinel", partialOutputReceived)
                        }
                        is CommandFrameReadOutcome.EndOfStream -> {
                            issue = LocalAdbReadIssue.END_OF_STREAM
                            partialOutputReceived = readResult.partialOutputReceived
                            failureSummary = commandFailureSummary(command, "EOF before sentinel", partialOutputReceived)
                        }
                        is CommandFrameReadOutcome.OutputLimitExceeded -> {
                            issue = LocalAdbReadIssue.OUTPUT_LIMIT
                            partialOutputReceived = readResult.partialOutputReceived
                            failureSummary = commandFailureSummary(command, "output limit reached before sentinel", partialOutputReceived)
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                issue = LocalAdbReadIssue.TIMEOUT
                partialOutputReceived = buffer.hasOutput
                failureSummary = commandFailureSummary(
                    command,
                    "timeout after ${command.timeoutMs / 1_000} seconds",
                    partialOutputReceived
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                issue = if (exception.message.orEmpty().contains("stream closed", ignoreCase = true)) {
                    LocalAdbReadIssue.STREAM_CLOSED
                } else {
                    LocalAdbReadIssue.IO_ERROR
                }
                partialOutputReceived = buffer.hasOutput
                failureSummary = "${command.safeLabel}: ${safeReadFailure(exception)}; partial output received: $partialOutputReceived."
            } finally {
                runCatching { stream?.close() }
            }

            LocalAdbCommandOutput(
                output = if (commandCompleted) output else "",
                issue = issue,
                failureSummary = failureSummary,
                commandKind = command.kind,
                commandCompleted = commandCompleted,
                exitCode = exitCode,
                partialOutputReceived = partialOutputReceived
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

    private fun commandFailureSummary(
        command: LocalAdbReadOnlyCommand,
        reason: String,
        partialOutputReceived: Boolean
    ): String =
        "${command.safeLabel}: $reason; partial output received: $partialOutputReceived."

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val FRAME_TERMINAL_OVERHEAD_BYTES = 256
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
