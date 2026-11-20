package com.camarodiag.gen5.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticAccountIsolationTest {
    private lateinit var database: DiagnosticDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DiagnosticDatabase::class.java
        ).build()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun diagnosticHistoryIsSeparatedByFirebaseUid() = runBlocking {
        val dao = database.sessionDao()
        val accountA = sample("account-A", "VIN-ACCOUNT-A")
        val accountB = sample("account-B", "VIN-ACCOUNT-B")
        dao.insertSession(accountA)
        dao.insertSession(accountB)

        val rowsA = dao.observeSessionsForOwner("account-A").first()
        val rowsB = dao.observeSessionsForOwner("account-B").first()
        assertEquals(listOf("VIN-ACCOUNT-A"), rowsA.map { it.vin })
        assertEquals(listOf("VIN-ACCOUNT-B"), rowsB.map { it.vin })

        dao.deleteAllForOwner("account-A")
        assertTrue(dao.observeSessionsForOwner("account-A").first().isEmpty())
        assertEquals(listOf("VIN-ACCOUNT-B"),
            dao.observeSessionsForOwner("account-B").first().map { it.vin })
    }

    @Test
    fun importingUnassignedHistoryDoesNotStealAnotherAccountsRecords() = runBlocking {
        val dao = database.sessionDao()
        dao.insertSession(sample("", "OLD-VIN"))
        dao.insertSession(sample("account-B", "B-VIN"))
        assertEquals(1, dao.countUnassignedSessions())

        dao.claimUnassignedSessions("account-A")
        assertEquals(0, dao.countUnassignedSessions())
        assertEquals(listOf("OLD-VIN"),
            dao.observeSessionsForOwner("account-A").first().map { it.vin })
        assertEquals(listOf("B-VIN"),
            dao.observeSessionsForOwner("account-B").first().map { it.vin })
    }

    private fun sample(uid: String, vin: String) = SavedSessionEntity(
        timestamp = 1L,
        vehicleLabel = "Camaro",
        vin = vin,
        adapterName = "Test interface",
        dtcSummary = "0 DTC",
        mileageSummary = "Not read",
        hasMileageDiscrepancy = false,
        maxDeltaKm = 0.0,
        ownerUid = uid
    )
}
