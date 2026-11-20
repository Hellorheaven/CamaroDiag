package com.camarodiag.gen5.control

import com.camarodiag.gen5.domain.model.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleControlCatalogAvailabilityTest {
    @Test fun allPresentControlsAreExplicitlyUnimplemented() {
        val controls = VehicleControlCatalog().forVehicle(VehicleProfile())
        assertEquals(VehicleControlId.entries.size, controls.size)
        assertEquals(VehicleControlId.entries.toSet(), controls.map { it.id }.toSet())
        assertTrue(controls.all { it.availability == ControlAvailability.NOT_IMPLEMENTED })
        assertFalse(controls.any { it.availability == ControlAvailability.AVAILABLE })
    }
}
