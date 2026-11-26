package com.stridecoach.running

import android.app.Application
import com.stridecoach.running.core.data.db.RunningDatabase
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import org.osmdroid.config.Configuration
import java.io.File

class StrideCoachApplication : Application() {
    lateinit var repository: StrideCoachRepository
        private set
    lateinit var preferencesStore: AppPreferencesStore
        private set

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = "StrideCoach/1.0 (+https://github.com/Avasay-Sayava/StrideCoach)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        val db = RunningDatabase.build(this)
        repository = StrideCoachRepository(db)
        preferencesStore = AppPreferencesStore(this)
    }
}
