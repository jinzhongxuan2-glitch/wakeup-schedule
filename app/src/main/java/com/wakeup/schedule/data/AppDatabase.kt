package com.wakeup.schedule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TimeTableEntity::class,
        CourseEntity::class,
        TimeSlotEntity::class,
        SectionTimeEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timeTableDao(): TimeTableDao
    abstract fun courseDao(): CourseDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun sectionTimeDao(): SectionTimeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2：课表新增外观字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timetables ADD COLUMN showWeekend INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE timetables ADD COLUMN blockAlpha REAL NOT NULL DEFAULT 0.95")
                db.execSQL("ALTER TABLE timetables ADD COLUMN bgType INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timetables ADD COLUMN bgValue TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wakeup.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
