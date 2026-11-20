package com.camarodiag.gen5.data.db

import android.content.Context
import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticDatabaseMigrationTest {
    @Test
    fun versionOneHistorySurvivesAccountOwnershipMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "camarodiag-migration-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL(
                    """CREATE TABLE IF NOT EXISTS diagnostic_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        vehicleLabel TEXT NOT NULL,
                        vin TEXT NOT NULL,
                        adapterName TEXT NOT NULL,
                        dtcSummary TEXT NOT NULL,
                        mileageSummary TEXT NOT NULL,
                        hasMileageDiscrepancy INTEGER NOT NULL,
                        maxDeltaKm REAL NOT NULL
                    )""".trimIndent()
                )
                old.insertOrThrow("diagnostic_sessions", null, ContentValues().apply {
                    put("timestamp", 1000L)
                    put("vehicleLabel", "2012 Camaro LFX")
                    put("vin", "LEGACY-VIN")
                    put("adapterName", "ELM327")
                    put("dtcSummary", "0 DTC")
                    put("mileageSummary", "Not read")
                    put("hasMileageDiscrepancy", 0)
                    put("maxDeltaKm", 0.0)
                })
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, DiagnosticDatabase::class.java, name)
                .addMigrations(DiagnosticDatabase.MIGRATION_1_2)
                .build()
            migrated.use { db ->
                assertEquals(1, db.sessionDao().countUnassignedSessions())
                assertTrue(db.sessionDao().observeSessionsForOwner("account-A").first().isEmpty())
                db.sessionDao().claimUnassignedSessions("account-A")
                assertEquals(
                    listOf("LEGACY-VIN"),
                    db.sessionDao().observeSessionsForOwner("account-A").first().map { it.vin }
                )
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
