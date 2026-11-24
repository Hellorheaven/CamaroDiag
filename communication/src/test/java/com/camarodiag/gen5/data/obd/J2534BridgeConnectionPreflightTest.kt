package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test

/** No socket, Windows driver or ECU is contacted by these tests. */
class J2534BridgeConnectionPreflightTest {
    private val pairing = "A".repeat(43) + "." + "a".repeat(64)

    private fun validate(
        host: String = "192.168.1.12",
        port: Int = 35001,
        code: String = pairing,
        request: Int = 0x7E0,
        response: Int = 0x7E8,
        can29Bit: Boolean = false,
        protocol: Int = 33
    ) = J2534BridgeConnectionPreflight.requireValid(
        host, port, code, request, response, can29Bit, protocol
    )

    @Test fun acceptsLocalPinnedJ2534BridgeWithCoherentIdentifiers() {
        validate()
        validate(host = "172.16.5.20", port = 35001,
            request = 0x18DAF110, response = 0x18DA10F1,
            can29Bit = true, protocol = 34)
        validate(host = "10.0.0.2", protocol = 35)
    }

    @Test fun invalidTlsPairingOrPublicAddressCannotReplaceWorkingAdapter() {
        for (host in listOf("8.8.8.8", "127.0.0.1", "example.net",
            "192.168.01.2", "192.168.0.999", "", "192.168.1.2\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(host = host)
            }
        }
        for (code in listOf("", "token", "a".repeat(43) + "." + "Z".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(code = code)
            }
        }
    }

    @Test fun invalidPortCanWidthOrProtocolIsRefusedBeforeTeardown() {
        for (port in listOf(0, 80, 65536)) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(port = port)
            }
        }
        for (protocol in listOf(0, 32, 37, 65)) {
            assertThrows(IllegalArgumentException::class.java) {
                validate(protocol = protocol)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(request = 0x7E0, response = 0x7E0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(request = 0x800)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(can29Bit = true, protocol = 33)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(can29Bit = false, protocol = 34)
        }
    }
}
