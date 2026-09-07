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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat

/**
 * Foreground service hosting agent task execution (audit 2026 fix).
 *
 * Gap-closure P1-20: the service previously (a) monitored the GLOBAL
 * `ExecutionHost.isRunning` boolean, so it could not tell WHICH execution it
 * was protecting, and (b) leaked a NEW collector on every onStartCommand.
 * It now tracks the per-execution REGISTRY ([ExecutionHost.activeExecutions])
 * through a SINGLE lifecycle-managed collector (started at most once), stops
 * itself the moment the registry empties, and its notification reflects the
 * live execution count.
 *
 * Android reality: without foreground priority, a long-running agent loop in
 * a backgrounded app is killed within seconds to minutes — mid-task process
 * death with no chance to checkpoint. While at least one execution is live,
 * this service:
 *   - elevates the process to FOREGROUND priority (survives backgrounding),
 *   - shows an honest progress notification the user can act on,
 *   - stops itself the moment the last execution completes/cancels.
 */
class AgentExecutionForegroundService : Service() {

    private var collectorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(executionCount = ExecutionHost.activeExecutions.value.size)
        // SINGLE lifecycle-managed collector (P1-20): no more collector leak
        // on repeated onStartCommand calls.
        if (collectorJob == null || collectorJob?.isActive != true) {
            collectorJob = ExecutionHost.scope.launch {
                ExecutionHost.activeExecutions.collect { live ->
                    if (live.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notifyExecutionCount(live.size)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        collectorJob = null
        super.onDestroy()
    }

    private fun startAsForeground(executionCount: Int) {
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
        val notification = buildNotification(executionCount)
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

    private fun notifyExecutionCount(count: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(count))
        }
    }

    private fun buildNotification(executionCount: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (executionCount > 1) {
            "$executionCount مهام ذكية قيد التنفيذ"
        } else {
            "مهمة ذكية قيد التنفيذ"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("يعمل الوكيل الآن على المهمة — لا تغلق التطبيق لضمان إكمال التنفيذ.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
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
