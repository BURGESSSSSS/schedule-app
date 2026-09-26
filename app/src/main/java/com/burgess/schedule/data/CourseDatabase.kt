package com.burgess.schedule.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CourseEntity::class, PickupCodeEntity::class],
    version = 7,
    autoMigrations = [AutoMigration(from = 5, to = 6)],
    exportSchema = true
)
abstract class CourseDatabase : RoomDatabase() {
    abstract fun courseDao(): CourseDao
    abstract fun pickupCodeDao(): PickupCodeDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE courses ADD COLUMN endPeriod INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE courses SET endPeriod = period WHERE endPeriod < period")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE courses ADD COLUMN weekNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "UPDATE courses SET period = (period / 2) * 2, endPeriod = ((period / 2) * 2) + 1"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS pickup_codes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "code TEXT NOT NULL, " +
                        "station TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "picked INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE courses ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'NON_SDBI'"
                )
                database.execSQL("ALTER TABLE courses ADD COLUMN sourceSemesterKey TEXT")
                database.execSQL("ALTER TABLE courses ADD COLUMN externalKey TEXT")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_courses_sourceType_sourceSemesterKey_externalKey " +
                        "ON courses (sourceType, sourceSemesterKey, externalKey)"
                )
            }
        }

        internal val supportedMigrations: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_6_7
        )

        @Volatile
        private var instance: CourseDatabase? = null

        fun getInstance(context: Context): CourseDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CourseDatabase::class.java,
                    "schedule.db"
                )
                    .addMigrations(*supportedMigrations)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
