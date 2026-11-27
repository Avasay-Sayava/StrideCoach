package com.stridecoach.running

import android.app.Application
import com.stridecoach.running.core.data.adapter.VaultAndTimelineSyncManager
import com.stridecoach.running.core.data.db.RunningDatabase
import com.stridecoach.running.core.data.health.HealthConnectSyncWorker
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.tracking.TrackingSessionBus
import com.stridecoach.running.ui.components.MapLibreAssetCache
import com.stridecoach.running.widget.LiveWorkoutWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class StrideCoachApplication : Application() {
    lateinit var repository: StrideCoachRepository
        private set
    lateinit var preferencesStore: AppPreferencesStore
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        TrackingSessionBus.widgetUpdater = { ctx -> LiveWorkoutWidgetProvider.refreshAll(ctx) }
        val db = RunningDatabase.build(this)
        repository = StrideCoachRepository(db)
        preferencesStore = AppPreferencesStore(this)
        appScope.launch {
            MapLibreAssetCache.warmUp(this@StrideCoachApplication)
            val prefs = preferencesStore.preferences.first()
            TrackingSessionBus.slopeAdjustedPaceEnabled = prefs.slopeAdjustedPaceEnabled
            TrackingSessionBus.widgetTapGuardEnabled = prefs.widgetTapGuardEnabled
            HealthConnectSyncWorker.setScheduled(
                this@StrideCoachApplication,
                prefs.healthAutoSyncEnabled || prefs.folderAutoSyncEnabled
            )
            if (prefs.folderAutoSyncEnabled && prefs.syncFolderUri.isNotBlank()) {
                runCatching {
                    VaultAndTimelineSyncManager.syncFolderNow(
                        this@StrideCoachApplication,
                        repository,
                        preferencesStore,
                        forceAll = false
                    )
                }
            }
        }
    }
}
