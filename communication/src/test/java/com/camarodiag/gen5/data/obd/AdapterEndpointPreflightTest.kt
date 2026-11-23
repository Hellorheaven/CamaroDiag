package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterEndpointPreflightTest {
    @Test fun acceptsOrdinaryWifiObdAdaptersWithoutChangingTheirAddresses() {
        val configured = AdapterEndpointPreflight.wifi(" 192.168.0.10 ", 35000)
        assertEquals("192.168.0.10", configured.host)
        assertEquals(35000, configured.port)
        assertEquals("obd.local", AdapterEndpointPreflight.wifi("obd.local", 35000).host)
    }

    @Test fun malformedWifiHostOrPortFailsBeforeNetworkConnection() {
        for (host in listOf(
            "", "https://192.168.0.10", "127.0.0.1:35000",
            "obd host", "user@obd.local", "192.168.0.10/path", "obd.local?query",
            "obd.local#fragment"
        )) {
            assertTrue("Unexpected accepted Wi-Fi host: $host", runCatching {
                AdapterEndpointPreflight.wifi(host, 35000)
            }.isFailure)
        }
        for (port in listOf(-1, 0, 65536)) {
            assertTrue(runCatching {
                AdapterEndpointPreflight.wifi("192.168.0.10", port)
            }.isFailure)
        }
    }

    @Test fun bluetoothMacIsCanonicalAndMalformedAddressIsRejected() {
        assertEquals("AA:BB:CC:11:22:33",
            AdapterEndpointPreflight.bluetoothMac("aa:bb:cc:11:22:33"))
        for (address in listOf(
            "", "not-an-address", "AA:BB:CC:DD:EE",
            "AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:GG"
        )) {
            assertTrue(runCatching { AdapterEndpointPreflight.bluetoothMac(address) }.isFailure)
        }
    }
}
