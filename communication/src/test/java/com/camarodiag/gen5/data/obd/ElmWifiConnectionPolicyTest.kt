package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test

class ElmWifiConnectionPolicyTest {
    @Test fun acceptsBoundedIPv4HostnamesAndIpv6Endpoints() {
        ElmWifiConnectionPolicy.validate("192.168.0.10", 35000, 4_000)
        ElmWifiConnectionPolicy.validate("obdlink.local", 23, 1)
        ElmWifiConnectionPolicy.validate("fe80::1", 65535, 30_000)
    }

    @Test fun rejectsUnboundedOrInvalidTcpConnectWithoutOpeningASocket() {
        for (timeout in listOf(0, -1, 30_001, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ElmWifiConnectionPolicy.validate("192.168.0.10", 35000, timeout)
            }
        }
        for (port in listOf(-1, 0, 65536)) {
            assertThrows(IllegalArgumentException::class.java) {
                ElmWifiConnectionPolicy.validate("192.168.0.10", port, 4_000)
            }
        }
    }

    @Test fun rejectsBlankOrControlCharacterHostnamesWithoutBlockingLegitimateDns() {
        for (host in listOf("", "  ", "host name", "192.168.0.10\n",
            "obd\r.local", "obd\u0000.local", "x".repeat(254))) {
            assertThrows(IllegalArgumentException::class.java) {
                ElmWifiConnectionPolicy.validate(host, 35000, 4_000)
            }
        }
    }
}
