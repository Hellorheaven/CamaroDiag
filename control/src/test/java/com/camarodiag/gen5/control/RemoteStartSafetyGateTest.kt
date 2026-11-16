package com.camarodiag.gen5.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStartSafetyGateTest {
    @Test fun unknownSignalsBlockRemoteStart() {
        val result = RemoteStartSafetyGate.assess(RemoteStartConditions(
            VehicleGear.UNKNOWN, VehicleSignal.UNKNOWN, VehicleSignal.UNKNOWN,
            VehicleSignal.UNKNOWN, VehicleSignal.UNKNOWN, VehicleSignal.UNKNOWN,
            VehicleSignal.UNKNOWN, VehicleSignal.UNKNOWN
        ))
        assertFalse(result.allowedToRequestOemProcedure)
        assertTrue(result.blockersFr.isNotEmpty())
        assertEquals(result.blockersFr.size, result.blockersEn.size)
        assertTrue(result.blockersEn.all { it.isNotBlank() })
    }

    @Test fun allVerifiedOemInterlocksAreRequired() {
        val result = RemoteStartSafetyGate.assess(RemoteStartConditions(
            VehicleGear.PARK, VehicleSignal.TRUE, VehicleSignal.TRUE,
            VehicleSignal.TRUE, VehicleSignal.TRUE, VehicleSignal.TRUE,
            VehicleSignal.TRUE, VehicleSignal.TRUE
        ))
        assertTrue(result.allowedToRequestOemProcedure)
    }
}
