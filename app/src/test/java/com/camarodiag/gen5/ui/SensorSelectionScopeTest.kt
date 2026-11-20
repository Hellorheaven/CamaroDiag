package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorSelectionScopeTest {
    @Test fun isolatesAccountsEvenWithSameVin() {
        val vehicle = "vin_2G1FF3D36C9104199"
        assertNotEquals(
            SensorSelectionScope.key("alice", vehicle),
            SensorSelectionScope.key("bob", vehicle)
        )
    }

    @Test fun isolatesGarageProfilesInOneAccount() {
        assertNotEquals(
            SensorSelectionScope.key("same-user", "garage_123"),
            SensorSelectionScope.key("same-user", "garage_456")
        )
        assertEquals(
            "account_same-user_vehicle_garage_123",
            SensorSelectionScope.key("same-user", "garage_123")
        )
    }

    @Test fun unknownUserOrVehicleNeverPersists() {
        assertNull(SensorSelectionScope.key(null, "garage_123"))
        assertNull(SensorSelectionScope.key("same-user", null))
        assertNull(SensorSelectionScope.key(" ", "garage_123"))
    }
}
