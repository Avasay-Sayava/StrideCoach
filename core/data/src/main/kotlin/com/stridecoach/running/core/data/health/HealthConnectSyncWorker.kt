package com.stridecoach.running.core.data.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stridecoach.running.core.data.db.RunningDatabase
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class HealthConnectSyncWorker(
    c: Context,
    wp: WorkerParameters
) : CoroutineWorker(c, wp) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = AppPreferencesStore(ctx).preferences.first()
        if (!p.healthConnectEnabled || !HealthConnectSyncManager.hasPermissions(ctx)) {
            return Result.success()
        }
        val repo = StrideCoachRepository(RunningDatabase.build(ctx))
        HealthConnectSyncManager.syncRecentDays(ctx, repo, days = 14L)
        return Result.success()
    }

    companion object {
        private const val JOB = "hc_sync"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(6L, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(JOB, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
