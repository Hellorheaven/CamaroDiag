package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class J2534StandardObdTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    @Test fun identityMustBelongToNegotiatedPhysicalResponder() {
        val vin = "1G1FA1RXXR0123456"
        fun identity(response: String = "7E8", expected: String = "7E8",
            id: String? = vin, cvn: String = "1234ABCD") =
            J2534StandardObd.validateIdentity(id,listOf("12664221"),listOf(cvn),
                listOf("ECM E39"),response,expected)
        assertEquals(vin,identity().vin)
        assertEquals(listOf("7E8"),identity().respondingEcuAddresses)
        assertEquals(listOf("18DAF110"),identity("18DAF110","18DAF110").respondingEcuAddresses)
        assertThrows(IllegalArgumentException::class.java) { identity("7E9") }
        assertThrows(IllegalArgumentException::class.java) { identity("800","800") }
        assertThrows(IllegalArgumentException::class.java) { identity("20000000","20000000") }
        assertThrows(IllegalArgumentException::class.java) { identity(id = "INVALID") }
        assertThrows(IllegalArgumentException::class.java) { identity(cvn = "1234") }
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.validateIdentity(null,emptyList(),emptyList(),emptyList(),"7E8","7E8")
        }
    }

    @Test fun canDtcCountIsNotPartOfFirstCode() {
        assertEquals(listOf(ObdDtc("P0300","confirmed"), ObdDtc("U0121","confirmed")),
            J2534StandardObd.dtcs(3,bytes(0x43,2,3,0,0xC1,0x21)))
        assertTrue(J2534StandardObd.dtcs(7,bytes(0x47,0)).isEmpty())
        assertEquals(listOf(ObdDtc("P0420","permanent")),
            J2534StandardObd.dtcs(10,bytes(0x4A,1,4,0x20)))
    }
    @Test fun neverConvertsInvalidOrNegativeResponseToAnEmptySuccessfulScan() {
        for (bad in listOf(bytes(0x43), bytes(0x43,2,3,0), bytes(0x43,0,0,0),
            bytes(0x43,1,0,0), bytes(0x47,0), bytes(0x7F,3,0x22))) {
            assertThrows(IllegalArgumentException::class.java) { J2534StandardObd.dtcs(3,bad) }
        }
    }
    @Test fun bitmapAndPidEchoAreExact() {
        assertEquals(setOf(1,12,31) to true,
            J2534StandardObd.supportPage(0,bytes(0x41,0,0x80,0x10,0,3)))
        assertArrayEquals(bytes(0x1A,0xF8),J2534StandardObd.pidData(0x0C,bytes(0x41,0x0C,0x1A,0xF8)))
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.supportPage(0,bytes(0x41,0,1,2,3,4,5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.pidData(0x0C,bytes(0x41,0x0D,0))
        }
    }
    @Test fun onlyExplicitUnsupportedNrcMayBeIdentifiedAsSuch() {
        assertTrue(J2534StandardObd.isExplicitlyUnsupported(7,bytes(0x7F,7,0x11)))
        assertFalse(J2534StandardObd.isExplicitlyUnsupported(7,bytes(0x7F,7,0x22)))
        assertFalse(J2534StandardObd.isExplicitlyUnsupported(7,bytes(0x7F,3,0x11)))
    }
    @Test fun unavailableOptionalModesAreNotEmptyPositiveResponses() {
        assertNull(J2534StandardObd.optionalDtcs(7,bytes(0x7F,7,0x11)))
        assertEquals(emptyList<ObdDtc>(),J2534StandardObd.optionalDtcs(7,bytes(0x47,0)))
        assertNull(J2534StandardObd.optionalDtcs(10,bytes(0x7F,10,0x12)))
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.optionalDtcs(3,bytes(0x7F,3,0x11))
        }
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.optionalDtcs(10,bytes(0x7F,10,0x22))
        }
        val snapshot = PhysicalObdDtcSnapshot(listOf(ObdDtc("P0300","confirmed")),null,emptyList())
        assertEquals(setOf(7),snapshot.unavailableModes)
        assertEquals(listOf(ObdDtc("P0300","confirmed")),snapshot.records)
    }
    @Test fun fuelAndEthanolTelemetryRejectsShortOrPaddedPayloads() {
        assertArrayEquals(bytes(0),J2534StandardObd.pidData(0x52,bytes(0x41,0x52,0)))
        assertArrayEquals(bytes(255),J2534StandardObd.pidData(0x52,bytes(0x41,0x52,255)))
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.pidData(0x52,bytes(0x41,0x52,128,0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.pidData(0x0C,bytes(0x41,0x0C,0x1A))
        }
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.pidData(0x44,bytes(0x41,0x44,0x80,0,0))
        }
        assertArrayEquals(bytes(0x80,0,0,0),
            J2534StandardObd.pidData(0x34,bytes(0x41,0x34,0x80,0,0,0)))
    }

    @Test fun noFunctionalOrSwCanChannel() {
        assertTrue(J2534StandardObd.supportsChannel(0x7E0,0x7E8,33))
        assertTrue(J2534StandardObd.supportsChannel(0x18DA10F1,0x18DAF110,34))
        assertFalse(J2534StandardObd.supportsChannel(0x7DF,0x7E8,33))
        assertFalse(J2534StandardObd.supportsChannel(0x7E0,0x7E8,63))
    }
    @Test fun j2534EthanolSupportBitIsDiscoveredOnPid40Page() {
        val (pids, continuation) = J2534StandardObd.supportPage(
            0x40, bytes(0x41, 0x40, 0x00, 0x00, 0x40, 0x00)
        )
        assertEquals(setOf(0x52), pids)
        assertFalse(continuation)
        assertArrayEquals(bytes(0x80), J2534StandardObd.pidData(
            0x52, bytes(0x41, 0x52, 0x80)))
        assertThrows(IllegalArgumentException::class.java) {
            J2534StandardObd.pidData(0x52, bytes(0x41, 0x53, 0x80))
        }
    }

    @Test fun j2534ConsoleReadOnlyResponsesMustMatchRequestedServiceAndPid() {
        val ethanol = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        J2534BridgeTransport.validateReadOnlyConsoleReply(ethanol,
            bytes(0x41, 0x52, 0x80))
        for (invalid in listOf(
            bytes(0x7F, 0x01, 0x11),
            bytes(0x41, 0x53, 0x80),
            bytes(0x41, 0x52),
            bytes(0x41, 0x52, 0x80, 0x00)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                J2534BridgeTransport.validateReadOnlyConsoleReply(ethanol, invalid)
            }
        }
        val vehicleInfo = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        J2534BridgeTransport.validateReadOnlyConsoleReply(vehicleInfo,
            bytes(0x49, 0x02, 0x01, 0x31))
        assertThrows(IllegalArgumentException::class.java) {
            J2534BridgeTransport.validateReadOnlyConsoleReply(vehicleInfo,
                bytes(0x49, 0x04, 0x01, 0x31))
        }
        val pending = requireNotNull(SafeObdRequestPolicy.parse("07"))
        J2534BridgeTransport.validateReadOnlyConsoleReply(pending, bytes(0x47, 0))
        assertThrows(IllegalArgumentException::class.java) {
            J2534BridgeTransport.validateReadOnlyConsoleReply(pending,
                bytes(0x7F, 0x07, 0x22))
        }
    }

    @Test fun fabricatedReadOnlyCommandObjectCannotAuthorizeBridgeTraffic() {
        val fake = SafeObdRequest(0x34, 0x00, "0152")
        assertThrows(IllegalArgumentException::class.java) {
            J2534BridgeTransport.validateReadOnlyConsoleReply(fake,
                bytes(0x41, 0x52, 0x80))
        }
    }

    @Test fun j2534ConsoleValidatesEveryReadOnlyDtcModeIndependently() {
        for ((command, positive, negative) in listOf(
            Triple("03", bytes(0x43, 0), bytes(0x43, 1, 3)),
            Triple("07", bytes(0x47, 0), bytes(0x7F, 7, 0x11)),
            Triple("0A", bytes(0x4A, 0), bytes(0x7F, 0x0A, 0x22))
        )) {
            val request = requireNotNull(SafeObdRequestPolicy.parse(command))
            J2534BridgeTransport.validateReadOnlyConsoleReply(request, positive)
            assertThrows(IllegalArgumentException::class.java) {
                J2534BridgeTransport.validateReadOnlyConsoleReply(request, negative)
            }
        }
    }

    @Test fun j2534ConsoleSupportBitmapCannotBeAcceptedWhenTruncated() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0100"))
        J2534BridgeTransport.validateReadOnlyConsoleReply(request,
            bytes(0x41, 0x00, 0, 0, 0, 0))
        assertThrows(IllegalArgumentException::class.java) {
            J2534BridgeTransport.validateReadOnlyConsoleReply(request,
                bytes(0x41, 0x00, 0, 0, 0))
        }
    }

    @Test fun j2534NegativeReadIsPreservedAsFailedEcuRxOnlyWhenExact() {
        val ethanol = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        assertTrue(J2534BridgeTransport.isMatchingNegativeConsoleReply(
            ethanol, bytes(0x7F, 0x01, 0x11)))
        for (bad in listOf(
            bytes(0x7F, 0x09, 0x11),
            bytes(0x7F, 0x01),
            bytes(0x7F, 0x01, 0x11, 0),
            bytes(0x7F, 0x01, 0),
            bytes(0x7F, 0x01, 0x78),
            bytes(0x41, 0x52, 0x80)
        )) {
            assertFalse(J2534BridgeTransport.isMatchingNegativeConsoleReply(ethanol, bad))
        }
        val forged = SafeObdRequest(0x34, 0, "0152")
        assertFalse(J2534BridgeTransport.isMatchingNegativeConsoleReply(
            forged, bytes(0x7F, 0x34, 0x11)))
    }

    @Test fun j2534NegativeVehicleIdentificationIsReportedWithoutFakeVin() {
        val readVin = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        assertTrue(J2534BridgeTransport.isMatchingNegativeConsoleReply(
            readVin, bytes(0x7F, 0x09, 0x12)))
        assertFalse(J2534BridgeTransport.isMatchingNegativeConsoleReply(
            readVin, bytes(0x7F, 0x01, 0x12)))
        assertThrows(IllegalArgumentException::class.java) {
            J2534BridgeTransport.validateReadOnlyConsoleReply(
                readVin, bytes(0x7F, 0x09, 0x12))
        }
    }

}
