package com.stridecoach.running.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RunEntity::class,
        TrackPointEntity::class,
        RunSplitEntity::class,
        DetectedSegmentEntity::class,
        WalkLogEntity::class,
        TrainingPlanEntity::class,
        PlannedDayEntity::class,
        WorkoutTemplateEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class RunningDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun walkDao(): WalkDao
    abstract fun planDao(): PlanDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN activityKind TEXT NOT NULL DEFAULT 'RUNNING'")
                db.execSQL("ALTER TABLE runs ADD COLUMN rawEncodedPolyline TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE runs ADD COLUMN snappedEncodedPolyline TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE runs ADD COLUMN isSnapped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN snapStreetPct INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN snapGreeneryPct INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN snapLoopsKept INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN strideStabilityPct INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN elevationLossMeters INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN minAltitudeMeters INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN maxAltitudeMeters INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE runs ADD COLUMN gradeAdjustedPaceSec INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN deletedAtMillis INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE workout_templates ADD COLUMN deletedAtMillis INTEGER DEFAULT NULL")
            }
        }

        fun build(context: Context): RunningDatabase = Room.databaseBuilder(
            context.applicationContext,
            RunningDatabase::class.java,
            "stridecoach.db"
        ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration().build()
    }
}
