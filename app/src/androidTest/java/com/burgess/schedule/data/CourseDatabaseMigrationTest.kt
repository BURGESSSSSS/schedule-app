package com.burgess.schedule.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseDatabaseMigrationTest {
    @Test
    fun migrationV5ToV6PreservesRowsAndEnablesShortName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stage7_migration_v5_v6_${UUID.randomUUID()}.db"
        assertNotEquals("schedule.db", databaseName)

        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val legacyDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacyDatabase.use { database ->
            database.execSQL(
                "CREATE TABLE courses (" +
                    "id INTEGER NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "teacher TEXT NOT NULL, " +
                    "room TEXT NOT NULL, " +
                    "dayIndex INTEGER NOT NULL, " +
                    "period INTEGER NOT NULL, " +
                    "endPeriod INTEGER NOT NULL DEFAULT 0, " +
                    "weekNumber INTEGER NOT NULL DEFAULT 0, " +
                    "colorValue INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))"
            )
            database.execSQL(
                "CREATE TABLE pickup_codes (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "code TEXT NOT NULL, " +
                    "station TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "picked INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            database.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf("2334b1d10af5e0a4eb275531f39ec80c")
            )
            database.execSQL(
                "INSERT INTO courses " +
                    "(id, name, teacher, room, dayIndex, period, endPeriod, weekNumber, colorValue) " +
                    "VALUES (101, 'Calculus', 'Lin', 'A-301', 1, 2, 3, 5, 4280391411)"
            )
            database.execSQL(
                "INSERT INTO pickup_codes (code, station, status, picked, createdAt) " +
                    "VALUES ('OLD-ROW', 'North Gate', 'pending', 0, 1720000000000)"
            )
            database.execSQL("PRAGMA user_version = 5")
        }

        val migratedDatabase = Room.databaseBuilder(
            context,
            CourseDatabase::class.java,
            databaseName
        ).build()
        try {
            assertEquals(6, migratedDatabase.openHelper.writableDatabase.version)
            runBlocking {
                val courses = migratedDatabase.courseDao().getAll()
                assertEquals(1, courses.size)
                assertEquals("Calculus", courses.single().name)
                assertEquals(5, courses.single().weekNumber)

                val pickups = migratedDatabase.pickupCodeDao().observeAll().first()
                assertEquals(1, pickups.size)
                assertEquals("OLD-ROW", pickups.single().code)

                migratedDatabase.courseDao().upsert(courses.single().copy(shortName = "MATH"))
                assertEquals("MATH", migratedDatabase.courseDao().getAll().single().shortName)
            }
            val shortNameColumn = migratedDatabase.openHelper.writableDatabase.query(
                "PRAGMA table_info(courses)"
            ).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == "shortName") found = true
                }
                found
            }
            assertEquals(true, shortNameColumn)
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }
}
