package com.example.application.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat

/**
 * Foreground service hosting agent task execution (audit 2026 fix).
 *
 * Android reality: without foreground priority, a long-running agent loop in
 * a backgrounded app is killed within seconds to minutes — mid-task process
 * death with no chance to checkpoint. While an execution is live, this
 * service:
 *   - elevates the process to FOREGROUND priority (survives backgrounding),
 *   - shows an honest progress notification the user can act on,
 *   - stops itself the moment the execution completes/cancels.
 *
 * It does not own the execution itself — `ExecutionHost` does — it exists to
 * satisfy Android's foreground-execution contract for that work.
 */
class AgentExecutionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        // Observe the host: stop when nothing is running anymore.
        ExecutionHost.scope.launch {
            ExecutionHost.isRunning.collect { running ->
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "تنفيذ مهام الوكلاء",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "إشعار التنفيذ الحي لمهام الوكلاء الذكية"
                }
            )
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مهمة ذكية قيد التنفيذ")
            .setContentText("يعمل الوكيل الآن على المهمة — لا تغلق التطبيق لضمان إكمال التنفيذ.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_execution_channel"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.action.STOP_EXECUTION_FG"

        /**
         * Starts the foreground shell when an execution begins. No-ops safely
         * when the platform or permission state disallows it (the execution
         * itself continues — the service is a durability aid, not a gate).
         */
        fun start(context: Context) {
            val intent = Intent(context, AgentExecutionForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Notification permission missing (API 33+) or app in a state
                // that forbids the start — honest degradation: work continues
                // without the foreground shell.
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentExecutionForegroundService::class.java)
            intent.action = ACTION_STOP
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Service already stopped / app teardown in progress.
            }
        }
    }
}
