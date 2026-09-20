package com.burgess.schedule.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.burgess.schedule.MainActivity
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.SchoolSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

enum class TestNotificationResult {
    SENT,
    NOTIFICATION_PERMISSION_REQUIRED,
    NOTIFICATIONS_DISABLED
}

data class ReminderStatus(
    val remindersEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean
)

object ReminderScheduler {
    private const val CHANNEL_ID = "course_reminders"
    private const val CHANNEL_NAME = "课程提醒"
    private const val PREFS_NAME = "reminder_preferences"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
    private const val KEY_LAST_DELIVERED_PREFIX = "last_delivered_"

    const val ACTION_REMINDER = "com.burgess.schedule.action.COURSE_REMINDER"

    internal const val EXTRA_ID = "course_id"
    internal const val EXTRA_NAME = "course_name"
    internal const val EXTRA_ROOM = "course_room"
    internal const val EXTRA_DAY = "course_day"
    internal const val EXTRA_PERIOD = "course_period"
    internal const val EXTRA_WEEK = "course_week"
    internal const val EXTRA_LEAD = "reminder_lead"
    internal const val EXTRA_OCCURRENCE_AT = "course_occurrence_at"

    const val DEFAULT_LEAD_MINUTES = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES

    private const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    private const val LATE_NOTIFICATION_DELAY_MILLIS = 1_000L

    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deliveryLock = Any()

    /** Existing behavior was enabled, so an absent key must remain enabled. */
    fun areRemindersEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_REMINDERS_ENABLED, true)
    }

    fun getStatus(context: Context): ReminderStatus {
        val appContext = context.applicationContext
        return ReminderStatus(
            remindersEnabled = areRemindersEnabled(appContext),
            notificationPermissionGranted = hasNotificationPermission(appContext),
            notificationsEnabled = notificationsEnabled(appContext),
            exactAlarmAllowed = canScheduleExactAlarms(appContext)
        )
    }

    /**
     * Persists the global switch. Re-enabling also reloads the database so a UI-only toggle
     * does not depend on a course list recomposition to restore alarms.
     */
    fun setRemindersEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        preferences(appContext).edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply()
        if (enabled) {
            rescheduleFromDatabase(appContext)
        } else {
            cancelAll(appContext)
        }
    }

    fun scheduleAll(
        context: Context,
        courses: List<CourseEntity>,
        leadMinutes: Int = DEFAULT_LEAD_MINUTES
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        cancelPersistedRequests(appContext)
        if (!areRemindersEnabled(appContext)) return

        val validCourses = courses.filter { isSchedulable(it) }
        preferences(appContext).edit()
            .putStringSet(
                KEY_SCHEDULED_IDS,
                validCourses.map { requestCode(it.id).toString() }.toSet()
            )
            .apply()
        validCourses.forEach { course ->
            scheduleDetails(
                appContext,
                course.id,
                course.name,
                course.room,
                course.dayIndex,
                course.period,
                course.weekNumber,
                leadMinutes
            )
        }
    }

    fun scheduleCourse(
        context: Context,
        course: CourseEntity,
        leadMinutes: Int = DEFAULT_LEAD_MINUTES
    ) {
        val appContext = context.applicationContext
        if (!areRemindersEnabled(appContext) || !isSchedulable(course)) return
        ensureChannel(appContext)
        addScheduledId(appContext, requestCode(course.id))
        scheduleDetails(
            appContext,
            course.id,
            course.name,
            course.room,
            course.dayIndex,
            course.period,
            course.weekNumber,
            leadMinutes
        )
    }

    fun scheduleNextFromReminder(
        context: Context,
        id: Long,
        name: String,
        room: String,
        dayIndex: Int,
        period: Int,
        weekNumber: Int,
        leadMinutes: Int
    ) {
        val appContext = context.applicationContext
        if (!areRemindersEnabled(appContext)) return
        if (!isSchedulable(dayIndex, period)) return
        addScheduledId(appContext, requestCode(id))
        scheduleDetails(appContext, id, name, room, dayIndex, period, weekNumber, leadMinutes)
    }

    internal fun scheduleFollowingRecurringReminder(
        context: Context,
        course: CourseEntity,
        occurrenceAt: Long,
        leadMinutes: Int
    ) {
        val appContext = context.applicationContext
        if (!areRemindersEnabled(appContext) || !isSchedulable(course)) return
        if (occurrenceAt <= 0L) {
            scheduleNextFromReminder(
                appContext,
                course.id,
                course.name,
                course.room,
                course.dayIndex,
                course.period,
                course.weekNumber,
                leadMinutes
            )
            return
        }
        val trigger = nextRecurringTrigger(
            Calendar.getInstance(),
            occurrenceAt,
            leadMinutes
        ) ?: return
        addScheduledId(appContext, requestCode(course.id))
        scheduleTriggerDetails(
            appContext,
            course.id,
            course.name,
            course.room,
            course.dayIndex,
            course.period,
            course.weekNumber,
            leadMinutes,
            trigger
        )
    }

    /** Starts an IO reload for UI callers after a permission or switch change. */
    fun rescheduleFromDatabase(context: Context) {
        val appContext = context.applicationContext
        schedulerScope.launch {
            rescheduleFromDatabaseNow(appContext)
        }
    }

    internal suspend fun rescheduleFromDatabaseNow(context: Context) {
        val appContext = context.applicationContext
        val courses = CourseDatabase.getInstance(appContext).courseDao().getAll()
        scheduleAll(appContext, courses)
    }

    fun sendTestNotification(context: Context): TestNotificationResult {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!hasNotificationPermission(appContext)) {
            return TestNotificationResult.NOTIFICATION_PERMISSION_REQUIRED
        }
        if (!notificationsEnabled(appContext)) {
            return TestNotificationResult.NOTIFICATIONS_DISABLED
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("课程提醒测试")
            .setContentText("通知功能已正常工作")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(appContext).notify(10001, notification)
            TestNotificationResult.SENT
        } catch (_: SecurityException) {
            TestNotificationResult.NOTIFICATION_PERMISSION_REQUIRED
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Returns the system exact-alarm settings Intent on Android S+, otherwise null. */
    fun exactAlarmPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
            Uri.parse("package:${context.packageName}")
        )
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        cancelPersistedRequests(appContext)
    }

    internal fun postReminder(context: Context, course: CourseEntity): Boolean {
        return postReminder(context, course.id, course.name, course.room, course.period)
    }

    internal fun postReminder(
        context: Context,
        id: Long,
        name: String,
        room: String,
        period: Int
    ): Boolean {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!areRemindersEnabled(appContext) || !canPostNotifications(appContext)) return false

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            requestCode(id) + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val location = room.ifBlank { "教室待填写" }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("下一节课：$name")
            .setContentText("${periodLabel(period)} · $location")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${periodLabel(period)} · $location"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(appContext).notify(requestCode(id), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun requestCode(id: Long): Int = (id xor (id ushr 32)).toInt() and 0x7fffffff

    internal fun matchesScheduledCourse(
        course: CourseEntity,
        dayIndex: Int,
        period: Int,
        weekNumber: Int
    ): Boolean {
        return course.dayIndex == dayIndex &&
            course.period == period &&
            course.weekNumber == weekNumber
    }

    internal fun nextTriggerTime(
        now: Calendar,
        dayIndex: Int,
        period: Int,
        weekNumber: Int,
        leadMinutes: Int
    ): Long? {
        return nextTrigger(now, dayIndex, period, weekNumber, leadMinutes)?.reminderAt
    }

    internal fun nextRecurringTriggerTime(
        now: Calendar,
        occurrenceAt: Long,
        leadMinutes: Int
    ): Long? {
        return nextRecurringTrigger(now, occurrenceAt, leadMinutes)?.reminderAt
    }

    internal fun claimDelivery(context: Context, id: Long, occurrenceAt: Long): Boolean {
        val effectiveOccurrence = occurrenceAt.takeIf { it > 0L }
            ?: (System.currentTimeMillis() / 60_000L)
        synchronized(deliveryLock) {
            val prefs = preferences(context)
            val key = KEY_LAST_DELIVERED_PREFIX + id
            if (prefs.getLong(key, Long.MIN_VALUE) == effectiveOccurrence) return false
            prefs.edit().putLong(key, effectiveOccurrence).apply()
            return true
        }
    }

    internal fun releaseDeliveryClaim(context: Context, id: Long, occurrenceAt: Long) {
        val effectiveOccurrence = occurrenceAt.takeIf { it > 0L }
            ?: (System.currentTimeMillis() / 60_000L)
        synchronized(deliveryLock) {
            val prefs = preferences(context)
            val key = KEY_LAST_DELIVERED_PREFIX + id
            if (prefs.getLong(key, Long.MIN_VALUE) == effectiveOccurrence) {
                prefs.edit().remove(key).apply()
            }
        }
    }

    internal fun cancelCourse(context: Context, id: Long) {
        val appContext = context.applicationContext
        cancelRequest(appContext, requestCode(id))
        val currentIds = preferences(appContext).getStringSet(KEY_SCHEDULED_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        if (currentIds.remove(requestCode(id).toString())) {
            preferences(appContext).edit().putStringSet(KEY_SCHEDULED_IDS, currentIds).apply()
        }
    }

    internal fun isRescheduleAction(action: String?): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED
    }

    private fun scheduleDetails(
        context: Context,
        id: Long,
        name: String,
        room: String,
        dayIndex: Int,
        period: Int,
        weekNumber: Int,
        leadMinutes: Int
    ) {
        if (!isSchedulable(dayIndex, period)) return
        val trigger = nextTrigger(
            Calendar.getInstance(),
            dayIndex,
            period,
            weekNumber,
            leadMinutes
        ) ?: return
        scheduleTriggerDetails(
            context,
            id,
            name,
            room,
            dayIndex,
            period,
            weekNumber,
            leadMinutes,
            trigger
        )
    }

    private fun scheduleTriggerDetails(
        context: Context,
        id: Long,
        name: String,
        room: String,
        dayIndex: Int,
        period: Int,
        weekNumber: Int,
        leadMinutes: Int,
        trigger: ReminderTrigger
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_ROOM, room)
            putExtra(EXTRA_DAY, dayIndex)
            putExtra(EXTRA_PERIOD, period)
            putExtra(EXTRA_WEEK, weekNumber)
            putExtra(EXTRA_LEAD, leadMinutes.coerceAtLeast(0))
            putExtra(EXTRA_OCCURRENCE_AT, trigger.classStartAt)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.reminderAt,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.reminderAt,
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            // Permission can change between the check and the call; keep a non-exact fallback.
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.reminderAt,
                    pendingIntent
                )
            } catch (_: SecurityException) {
                // The receiver will be retried by a later boot/time/permission reschedule.
            }
        }
    }

    private fun nextRecurringTrigger(
        now: Calendar,
        occurrenceAt: Long,
        leadMinutes: Int
    ): ReminderTrigger? {
        if (occurrenceAt <= 0L) return null
        val normalizedLead = leadMinutes.coerceAtLeast(0)
        val target = Calendar.getInstance(now.timeZone).apply {
            timeInMillis = occurrenceAt
            add(Calendar.DAY_OF_MONTH, 7)
        }
        var reminder = (target.clone() as Calendar).apply {
            add(Calendar.MINUTE, -normalizedLead)
        }
        while (reminder.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 7)
            reminder = (target.clone() as Calendar).apply {
                add(Calendar.MINUTE, -normalizedLead)
            }
        }
        return ReminderTrigger(target.timeInMillis, reminder.timeInMillis)
    }

    private fun nextTrigger(
        now: Calendar,
        dayIndex: Int,
        period: Int,
        weekNumber: Int,
        leadMinutes: Int
    ): ReminderTrigger? {
        if (!isSchedulable(dayIndex, period)) return null
        val periodWindow = SchoolSchedule.periodAt(period) ?: return null
        val normalizedLead = leadMinutes.coerceAtLeast(0)
        val target = if (weekNumber > 0) {
            SchoolSchedule.firstWeekStart(now.timeZone).apply {
                add(Calendar.WEEK_OF_YEAR, weekNumber - 1)
                add(Calendar.DAY_OF_MONTH, dayIndex)
                setStartTime(periodWindow.startMinutes)
            }
        } else {
            val targetDay = if (dayIndex == 6) Calendar.SUNDAY else Calendar.MONDAY + dayIndex
            val daysAhead = (targetDay - now.get(Calendar.DAY_OF_WEEK) + 7) % 7
            (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, daysAhead)
                setStartTime(periodWindow.startMinutes)
            }
        }

        var reminder = (target.clone() as Calendar).apply {
            add(Calendar.MINUTE, -normalizedLead)
        }
        if (target.timeInMillis > now.timeInMillis) {
            val reminderAt = if (reminder.timeInMillis <= now.timeInMillis) {
                minOf(
                    now.timeInMillis + LATE_NOTIFICATION_DELAY_MILLIS,
                    target.timeInMillis - 1L
                )
            } else {
                reminder.timeInMillis
            }
            if (reminderAt > now.timeInMillis) {
                return ReminderTrigger(target.timeInMillis, reminderAt)
            }
        }
        if (weekNumber > 0) {
            return null
        }
        while (reminder.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 7)
            reminder = (target.clone() as Calendar).apply {
                add(Calendar.MINUTE, -normalizedLead)
            }
        }
        return ReminderTrigger(target.timeInMillis, reminder.timeInMillis)
    }

    private fun Calendar.setStartTime(minutes: Int) {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun isSchedulable(course: CourseEntity): Boolean {
        return isSchedulable(course.dayIndex, course.period)
    }

    private fun isSchedulable(dayIndex: Int, period: Int): Boolean {
        return SchoolSchedule.isValidDayIndex(dayIndex) && SchoolSchedule.periodAt(period) != null
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun addScheduledId(context: Context, requestCode: Int) {
        val ids = preferences(context).getStringSet(KEY_SCHEDULED_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        if (ids.add(requestCode.toString())) {
            preferences(context).edit().putStringSet(KEY_SCHEDULED_IDS, ids).apply()
        }
    }

    private fun cancelPersistedRequests(context: Context) {
        val ids = preferences(context).getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        ids.mapNotNull { it.toIntOrNull() }.forEach { cancelRequest(context, it) }
        preferences(context).edit().remove(KEY_SCHEDULED_IDS).apply()
    }

    private fun cancelRequest(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        cancelPendingIntent(
            context,
            alarmManager,
            requestCode,
            Intent(context, ReminderReceiver::class.java).apply { action = ACTION_REMINDER }
        )
        // Alarms created by the previous implementation had no action. Remove those once too.
        cancelPendingIntent(
            context,
            alarmManager,
            requestCode,
            Intent(context, ReminderReceiver::class.java)
        )
    }

    private fun cancelPendingIntent(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        intent: Intent
    ) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "上课前的课程通知"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canPostNotifications(context: Context): Boolean {
        return hasNotificationPermission(context) && notificationsEnabled(context)
    }

    private fun notificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun periodLabel(period: Int): String =
        "第 ${period.coerceIn(0, SchoolSchedule.PERIOD_COUNT - 1) + 1} 节"

    private data class ReminderTrigger(
        val classStartAt: Long,
        val reminderAt: Long
    )
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!ReminderScheduler.areRemindersEnabled(appContext)) return@launch
                if (!intent.hasExtra(ReminderScheduler.EXTRA_ID)) return@launch

                val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, Long.MIN_VALUE)
                val dayIndex = intent.getIntExtra(ReminderScheduler.EXTRA_DAY, Int.MIN_VALUE)
                val period = intent.getIntExtra(ReminderScheduler.EXTRA_PERIOD, Int.MIN_VALUE)
                val weekNumber = intent.getIntExtra(ReminderScheduler.EXTRA_WEEK, Int.MIN_VALUE)
                val course = CourseDatabase.getInstance(appContext).courseDao().getAll()
                    .firstOrNull { it.id == id }

                if (
                    course == null ||
                    !ReminderScheduler.matchesScheduledCourse(course, dayIndex, period, weekNumber)
                ) {
                    return@launch
                }

                val occurrenceAt = intent.getLongExtra(ReminderScheduler.EXTRA_OCCURRENCE_AT, 0L)
                val leadMinutes = intent.getIntExtra(
                    ReminderScheduler.EXTRA_LEAD,
                    ReminderScheduler.DEFAULT_LEAD_MINUTES
                ).coerceAtLeast(0)

                if (
                    !ReminderScheduler.claimDelivery(
                        appContext,
                        id,
                        occurrenceAt
                    )
                ) {
                    if (course.weekNumber <= 0) {
                        ReminderScheduler.scheduleFollowingRecurringReminder(
                            appContext,
                            course,
                            occurrenceAt,
                            leadMinutes
                        )
                    }
                    return@launch
                }

                val delivered = try {
                    ReminderScheduler.postReminder(appContext, course)
                } catch (_: Exception) {
                    false
                }
                if (!delivered) {
                    ReminderScheduler.releaseDeliveryClaim(appContext, id, occurrenceAt)
                }
                if (course.weekNumber <= 0) {
                    ReminderScheduler.scheduleFollowingRecurringReminder(
                        appContext,
                        course,
                        occurrenceAt,
                        leadMinutes
                    )
                }
            } catch (_: Exception) {
                // A database/read failure must never turn stale Intent extras into a notification.
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderScheduler.isRescheduleAction(intent.action)) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.rescheduleFromDatabaseNow(appContext)
            } catch (_: Exception) {
                // Keep the receiver short-lived; the next lifecycle/time/permission event retries.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
