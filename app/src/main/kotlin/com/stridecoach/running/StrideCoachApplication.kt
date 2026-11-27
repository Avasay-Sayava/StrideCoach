package com.stridecoach.running

import android.app.Application
import com.stridecoach.running.core.data.adapter.VaultAndTimelineSyncManager as Vault
import com.stridecoach.running.core.data.db.RunningDatabase as Db
import com.stridecoach.running.core.data.health.HealthConnectSyncWorker as Sync
import com.stridecoach.running.core.data.repository.AppPreferencesStore as Store
import com.stridecoach.running.core.data.repository.CoachPreferences as Prefs
import com.stridecoach.running.core.data.repository.StrideCoachRepository as Repo
import com.stridecoach.running.core.tracking.TrackingSessionBus as Bus
import com.stridecoach.running.ui.components.MapLibreAssetCache as Assets
import com.stridecoach.running.widget.LiveWorkoutWidgetProvider as Wdg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class StrideCoachApplication : Application() {
    lateinit var repository: Repo
        private set
    lateinit var preferencesStore: Store
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        Bus.widgetUpdater = { c -> Wdg.refreshAll(c) }
        repository = Repo(Db.build(this))
        preferencesStore = Store(this)
        scope.launch { boot() }
    }

    private suspend fun boot() {
        Assets.warmUp(this)
        val p = preferencesStore.preferences.first()
        Bus.slopeAdjustedPaceEnabled = p.slopeAdjustedPaceEnabled
        Bus.widgetTapGuardEnabled = p.widgetTapGuardEnabled
        Sync.setScheduled(this, p.healthAutoSyncEnabled || p.folderAutoSyncEnabled)
        sync(p)
    }

    private suspend fun sync(p: Prefs) {
        if (!p.folderAutoSyncEnabled || p.syncFolderUri.isBlank()) return
        runCatching { Vault.syncFolderNow(this, repository, preferencesStore, forceAll = false) }
    }
}
