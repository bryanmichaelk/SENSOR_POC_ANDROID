package com.bryan.sensorpoc

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UsageSnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        if (appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), applicationContext.packageName) != AppOpsManager.MODE_ALLOWED) return Result.failure()
        val manager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 86_400_000, end).orEmpty()
        val recorder = SessionRecorder(applicationContext.filesDir)
        recorder.start(MainActivity.Source.USAGE_STATS, android.os.Build.MANUFACTURER, android.os.Build.MODEL, "workmanager")
        stats.forEach { recorder.append(mapOf("package" to it.packageName, "foregroundMs" to it.totalTimeInForeground, "lastUsed" to it.lastTimeUsed)) }
        recorder.stop("workmanager_usage_snapshot")
        return Result.success()
    }
}
