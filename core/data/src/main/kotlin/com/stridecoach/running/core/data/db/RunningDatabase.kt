package com.stridecoach.running.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RunEntity::class,
        TrackPointEntity::class,
        RunSplitEntity::class,
        WalkLogEntity::class,
        TrainingPlanEntity::class,
        PlannedDayEntity::class,
        WorkoutTemplateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RunningDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun walkDao(): WalkDao
    abstract fun planDao(): PlanDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao

    companion object {
        fun build(context: Context): RunningDatabase = Room.databaseBuilder(
            context.applicationContext,
            RunningDatabase::class.java,
            "stridecoach.db"
        ).fallbackToDestructiveMigration().build()
    }
}
