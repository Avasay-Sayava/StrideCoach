package com.stridecoach.running

import android.app.Application
import com.stridecoach.running.core.data.db.RunningDatabase
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.StrideCoachRepository

class StrideCoachApplication : Application() {
    lateinit var repository: StrideCoachRepository
        private set
    lateinit var preferencesStore: AppPreferencesStore
        private set

    override fun onCreate() {
        super.onCreate()
        val db = RunningDatabase.build(this)
        repository = StrideCoachRepository(db)
        preferencesStore = AppPreferencesStore(this)
    }
}
