package com.stridecoach.running.core.data.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stridecoach.running.core.data.adapter.VaultAndTimelineSyncManager
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
        val store = AppPreferencesStore(ctx)
        val p = store.preferences.first()
        val repo = StrideCoachRepository(RunningDatabase.build(ctx))

        if (p.healthAutoSyncEnabled && HealthConnectSyncManager.hasPermissions(ctx)) {
            HealthConnectSyncManager.syncRecentDays(ctx, repo, days = 14L)
            store.update { it.copy(lastHealthSyncMillis = System.currentTimeMillis()) }
        }
        if (p.folderAutoSyncEnabled && p.syncFolderUri.isNotBlank()) {
            VaultAndTimelineSyncManager.syncFolderNow(ctx, repo, store, forceAll = false)
        }
        return Result.success()
    }

    companion object {
        private const val JOB = "hc_sync"

        fun schedule(ctx: Context) {
            setScheduled(ctx, true)
        }

        fun setScheduled(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) {
                wm.cancelUniqueWork(JOB)
                return
            }
            val req = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(6L, TimeUnit.HOURS).build()
            wm.enqueueUniquePeriodicWork(JOB, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncNowOneShot(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<HealthConnectSyncWorker>().build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
