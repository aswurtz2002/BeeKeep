package com.beekeep.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import kotlinx.coroutines.launch
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beekeep.app.MainActivity
import com.beekeep.app.R
import com.beekeep.app.data.Task
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "beekeep_tasks"
private const val CHANNEL_NAME = "BeeKeep reminders"
private const val ACTION_REMINDER = "com.beekeep.app.ACTION_TASK_REMINDER"

object ReminderScheduler {
    fun exactAlarmAvailable(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } else true

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Inspection and treatment reminders"
            }
        )
    }

    fun schedule(context: Context, taskId: Long, title: String, dueAt: Long) {
        ensureChannel(context)
        WorkManager.getInstance(context).cancelUniqueWork("task_$taskId")
        cancelAlarmOnly(context, taskId)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = reminderPendingIntent(context, taskId, title, dueAt)
        if (alarmManager != null) {
            val triggerAt = dueAt.coerceAtLeast(System.currentTimeMillis() + 250L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (_: SecurityException) {
                // Exact-alarm access can be revoked between the capability check and scheduling.
                // Prefer a safe inexact alarm, then WorkManager if even that fails.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                } catch (_: SecurityException) {
                    scheduleWorkFallback(context, taskId, title, dueAt)
                }
            }
        } else {
            scheduleWorkFallback(context, taskId, title, dueAt)
        }
    }

    fun scheduleOutstanding(context: Context, tasks: List<Task>) {
        tasks.asSequence()
            .filter { !it.completed && it.reminderEnabled && it.dueAt > System.currentTimeMillis() }
            .forEach { schedule(context, it.id, it.title, it.dueAt) }
    }

    fun cancel(context: Context, taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("task_$taskId")
        cancelAlarmOnly(context, taskId)
    }

    private fun cancelAlarmOnly(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(reminderPendingIntent(context, taskId, ""))
    }

    private fun reminderPendingIntent(context: Context, taskId: Long, title: String, dueAt: Long = 0L): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra("task_id", taskId)
            putExtra("title", title)
            putExtra("due_at", dueAt)
        }
        return PendingIntent.getBroadcast(
            context,
            (taskId xor (taskId ushr 32)).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleWorkFallback(context: Context, taskId: Long, title: String, dueAt: Long) {
        val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf("task_id" to taskId, "title" to title, "due_at" to dueAt))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("task_$taskId", ExistingWorkPolicy.REPLACE, request)
    }
}

class TaskReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val taskId = intent.getLongExtra("task_id", 0L)
                if (taskId != 0L) notifyTaskIfCurrent(context, taskId, intent.getLongExtra("due_at", 0L))
            } finally {
                pending.finish()
            }
        }
    }
}

private suspend fun notifyTaskIfCurrent(context: Context, taskId: Long, scheduledDueAt: Long) {
    val task = com.beekeep.app.data.BeeKeepRoomDb.get(context).tasks().get(taskId) ?: return
    if (task.completed || !task.reminderEnabled) return
    if (scheduledDueAt > 0L && kotlin.math.abs(task.dueAt - scheduledDueAt) > 30_000L) return

    ReminderScheduler.ensureChannel(context)
    val openApp = PendingIntent.getActivity(
        context,
        (taskId xor 0x55AA55AAL).toInt(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_beekeep)
        .setContentTitle("BeeKeep reminder")
        .setContentText(task.title)
        .setContentIntent(openApp)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .build()
    try {
        context.getSystemService(NotificationManager::class.java)?.notify((taskId and 0x7FFFFFFF).toInt(), notification)
    } catch (_: SecurityException) {
        // Android 13+ notification permission may still be disabled.
    }
}

class TaskReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("task_id", 0L)
        if (taskId == 0L) return Result.failure()
        notifyTaskIfCurrent(applicationContext, taskId, inputData.getLong("due_at", 0L))
        return Result.success()
    }
}
