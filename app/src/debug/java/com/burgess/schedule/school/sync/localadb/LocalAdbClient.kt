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
private const val MAX_SOCKET_SCAN_OUTPUT_BYTES = 96 * 1024

internal enum class LocalAdbReadOnlyCommand(
    internal val shellInput: String,
    internal val outputLimit: Int,
    internal val isComplete: (String) -> Boolean
) {
    ID("id\n", MAX_ID_OUTPUT_CHARS, ::hasCompleteUidField),
    PROCESS_SOCKET_AUDIT(
        PROCESS_SOCKET_AUDIT_SCRIPT.replace('§', '$'),
        MAX_SOCKET_SCAN_OUTPUT_BYTES,
        ::hasLocalAdbScanFinished
    )
}

private val PROCESS_SOCKET_AUDIT_SCRIPT = """
{
  awk 'NR == 1 { next } {
    if (NF >= 8 && §8 != "") {
      named++;
      if (substr(§8, 1, 1) == "@") abstract++;
    }
  } END { printf "__PROC_UNIX_SUMMARY__|%d|%d|%d\n", NR, named, abstract }' /proc/net/unix 2>/dev/null
  printf '__PROC_UNIX_EXIT__|%s\n' "§?"

  ps_output="$(ps -A -o PID,NAME 2>/dev/null)"
  ps_status=$?
  if [ "§ps_status" -eq 0 ]; then
    process_rows="$(printf '%s\n' "§ps_output" | awk '
    §1 ~ /^[0-9]+§$/ && ( §2 == "com.tencent.wework" || index(§2, "com.tencent.wework:") == 1 ) {
      printf "%s|%s\n", §1, §2
    }
  ')"
  fi
  if [ "§ps_status" -ne 0 ]; then
    printf '__WXWORK_QUERY_FAILED__\n'
  else
    printf '%s\n' "§process_rows" | while IFS='|' read -r pid process_name; do
      [ -n "§pid" ] || continue
      case "§pid" in *[!0-9]*) continue ;; esac
      case "§process_name" in
        com.tencent.wework|com.tencent.wework:*) ;;
        *) continue ;;
      esac

      printf '__WXWORK_PROCESS__|%s|%s\n' "§pid" "§process_name"
      fd_dir="/proc/§pid/fd"
      if [ ! -d "§fd_dir" ]; then
        printf '__PROC_FD_PROCESS_GONE__|%s\n' "§pid"
      elif ! ls "§fd_dir" >/dev/null 2>&1; then
        if [ -d "§fd_dir" ]; then
          printf '__PROC_FD_PERMISSION_DENIED__|%s\n' "§pid"
        else
          printf '__PROC_FD_PROCESS_GONE__|%s\n' "§pid"
        fi
      else
        fd_problem=0
        for fd_path in "§fd_dir"/*; do
          [ -e "§fd_path" ] || [ -L "§fd_path" ] || continue
          target="$(readlink "§fd_path" 2>/dev/null)"
          readlink_status=$?
          if [ "§readlink_status" -ne 0 ]; then
            fd_problem=1
            printf '__PROC_FD_ENTRY_UNREADABLE__|%s\n' "§pid"
            continue
          fi
          case "§target" in
            socket:\[*\])
              inode="§{target#socket:}"
              inode="§{inode#\[}"
              inode="§{inode%\]}"
              case "§inode" in *[!0-9]*|'') continue ;; esac
              printf '__FD_SOCKET__|%s|%s\n' "§pid" "§inode"
              ;;
          esac
        done
        if [ ! -d "§fd_dir" ]; then
          printf '__PROC_FD_PROCESS_GONE__|%s\n' "§pid"
        elif [ "§fd_problem" -eq 0 ]; then
          printf '__PROC_FD_OK__|%s\n' "§pid"
        fi
      fi

      case "§process_name" in
        com.tencent.wework|com.tencent.wework:wxa_container0)
          maps_file="/proc/§pid/maps"
          if [ -r "§maps_file" ]; then
            awk -v pid="§pid" '
              {
                path = ""
                for (i = 6; i <= NF; i++) path = path (i == 6 ? "" : " ") §i
                if (path !~ /^\//) next
                count = split(path, parts, "/")
                basename = parts[count]
                lowered = tolower(basename)
                keys = ""
                if (lowered ~ /xweb/) keys = keys "xweb,"
                if (lowered ~ /webview/) keys = keys "webview,"
                if (lowered ~ /chromium/) keys = keys "chromium,"
                if (lowered ~ /tbs/) keys = keys "tbs,"
                if (lowered ~ /x5/) keys = keys "x5,"
                if (keys != "") {
                  sub(/,$/, "", keys)
                  gsub(/[|[:cntrl:]]/, "?", basename)
                  printf "__WX_MAPS_LIBRARY__|%s|%s|%s\n", pid, basename, keys
                }
              }
            ' "§maps_file" 2>/dev/null
            maps_status=$?
            if [ "§maps_status" -eq 0 ]; then
              printf '__PROC_MAPS_READ_OK__|%s\n' "§pid"
            else
              printf '__PROC_MAPS_READ_FAILED__|%s\n' "§pid"
            fi
          else
            printf '__PROC_MAPS_READ_FAILED__|%s\n' "§pid"
          fi
          ;;
      esac
    done
    printf '__WXWORK_QUERY_DONE__\n'
  fi
} | awk '
  BEGIN {
    while ((getline unix_line < "/proc/net/unix") > 0) {
      unix_lines++
      fields = split(unix_line, unix_fields, " ")
      if (fields >= 7 && unix_fields[7] ~ /^[0-9]+$/) {
        socket_present[unix_fields[7]] = 1
        socket_name = ""
        if (fields >= 8) {
          socket_name = unix_fields[8]
          for (i = 9; i <= fields; i++) socket_name = socket_name " " unix_fields[i]
        }
        socket_names[unix_fields[7]] = socket_name
      }
    }
    close("/proc/net/unix")
    if (unix_lines > 0) print "__PROC_UNIX_MAP_OK__"
    else print "__PROC_UNIX_MAP_FAILED__"
  }
  §1 == "__FD_SOCKET__" && §2 ~ /^[0-9]+$/ && §3 ~ /^[0-9]+$/ {
    inode = §3
    socket_name = socket_names[inode]
    gsub(/[|[:cntrl:]]/, "?", socket_name)
    found = (inode in socket_present) ? 1 : 0
    printf "__OWNED_SOCKET__|%s|%s|%d|%s\n", §2, inode, found, socket_name
    next
  }
  { print }
'
printf '__LOCAL_ADB_SCAN_DONE__\n'
""".trimIndent()

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
