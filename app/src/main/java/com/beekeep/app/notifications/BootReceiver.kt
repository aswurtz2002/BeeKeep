package com.beekeep.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import com.beekeep.app.data.BeeKeepRoomDb
import com.beekeep.app.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shouldReschedule = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!shouldReschedule) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entities = BeeKeepRoomDb.get(context).tasks().pendingAfter(System.currentTimeMillis())
                val tasks = entities.map { Task(it.id, it.hiveId, it.title, it.dueAt, it.completed, it.kind, it.reminderEnabled) }
                ReminderScheduler.scheduleOutstanding(context, tasks)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
