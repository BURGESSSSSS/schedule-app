package com.burgess.schedule.school.sync.localadb

/** Commands used only by the development diagnostics screen. */
internal enum class DebugLocalAdbCommandKind(override val safeLabel: String) : LocalAdbCommandDescriptor {
    PRINTF_TEST("printf test"),
    FD_SCAN("/proc/PID/fd scan"),
    MAPS_SCAN("filtered /proc/PID/maps scan")
}

internal val LocalAdbReadOnlyCommand.Companion.PRINTF_TEST: LocalAdbReadOnlyCommand
    get() = LocalAdbReadOnlyCommand(
        kind = DebugLocalAdbCommandKind.PRINTF_TEST,
        shellCommand = "printf test",
        outputLimit = 1024
    )

internal fun LocalAdbReadOnlyCommand.Companion.fdScan(pid: Int): LocalAdbReadOnlyCommand {
    require(pid > 0) { "PID must be positive" }
    val command = FD_SCAN_SCRIPT
        .replace("@@PID@@", pid.toString())
        .replace('§', '$')
    return LocalAdbReadOnlyCommand(
        kind = DebugLocalAdbCommandKind.FD_SCAN,
        shellCommand = command,
        outputLimit = 32 * 1024,
        targetPid = pid
    )
}

internal fun LocalAdbReadOnlyCommand.Companion.mapsScan(pid: Int): LocalAdbReadOnlyCommand {
    require(pid > 0) { "PID must be positive" }
    val command = MAPS_SCAN_SCRIPT
        .replace("@@PID@@", pid.toString())
        .replace('§', '$')
    return LocalAdbReadOnlyCommand(
        kind = DebugLocalAdbCommandKind.MAPS_SCAN,
        shellCommand = command,
        outputLimit = 16 * 1024,
        targetPid = pid
    )
}

private val FD_SCAN_SCRIPT = """
if [ ! -d "/proc/@@PID@@/fd" ]; then
  printf '__FD_SCAN_FAILED__|@@PID@@|PROCESS_GONE\n'
elif ! ls "/proc/@@PID@@/fd" >/dev/null 2>&1; then
  if [ ! -d "/proc/@@PID@@/fd" ]; then
    printf '__FD_SCAN_FAILED__|@@PID@@|PROCESS_GONE\n'
  else
    printf '__FD_SCAN_PERMISSION_DENIED__|@@PID@@\n'
  fi
else
  fd_permission_denied=0
  fd_scan_failed=0
  for fd_path in /proc/@@PID@@/fd/*; do
    [ -e "§fd_path" ] || [ -L "§fd_path" ] || continue
    target_result="$(readlink "§fd_path" 2>&1)"
    readlink_status=$?
    if [ "§readlink_status" -ne 0 ]; then
      case "§target_result" in
        *"Permission denied"*) fd_permission_denied=1 ;;
        *"No such file"*) ;;
        *) fd_scan_failed=1 ;;
      esac
      continue
    fi
    target="§target_result"
    case "§target" in
      socket:\[*\])
        inode="§{target#socket:}"
        inode="§{inode#\[}"
        inode="§{inode%\]}"
        case "§inode" in *[!0-9]*|'') continue ;; esac
        printf '__FD_SOCKET__|@@PID@@|%s|socket:[%s]\n' "§{fd_path##*/}" "§inode"
        ;;
    esac
  done
  if [ ! -d "/proc/@@PID@@/fd" ]; then
    printf '__FD_SCAN_FAILED__|@@PID@@|PROCESS_GONE\n'
  elif [ "§fd_permission_denied" -ne 0 ]; then
    printf '__FD_SCAN_PERMISSION_DENIED__|@@PID@@\n'
  elif [ "§fd_scan_failed" -ne 0 ]; then
    printf '__FD_SCAN_FAILED__|@@PID@@|READLINK_FAILED\n'
  else
    printf '__FD_SCAN_SUCCESS__|@@PID@@\n'
  fi
fi
""".trimIndent()

private val MAPS_SCAN_SCRIPT = """
maps_file="/proc/@@PID@@/maps"
if [ ! -r "§maps_file" ]; then
  printf '__MAPS_SCAN_PERMISSION_DENIED__|@@PID@@\n'
else
  maps_result="$(awk -v pid=@@PID@@ '
    {
      path = ""
      for (i = 6; i <= NF; i++) path = path (i == 6 ? "" : " ") §i
      sub(/ \(deleted\)$/, "", path)
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
  ' "§maps_file" 2>&1)"
  maps_status=$?
  case "§maps_result" in
    *"Permission denied"*) printf '__MAPS_SCAN_PERMISSION_DENIED__|@@PID@@\n' ;;
    *)
      printf '%s\n' "§maps_result" | awk '/^__WX_MAPS_LIBRARY__\|/'
      if [ "§maps_status" -eq 0 ]; then
        printf '__MAPS_SCAN_SUCCESS__|@@PID@@\n'
      else
        printf '__MAPS_SCAN_FAILED__|@@PID@@\n'
      fi
      ;;
  esac
fi
""".trimIndent()
