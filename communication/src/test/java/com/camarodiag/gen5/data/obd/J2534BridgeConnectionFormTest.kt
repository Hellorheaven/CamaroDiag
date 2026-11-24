package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class J2534BridgeConnectionFormTest {
    private val pairing = "a".repeat(43) + "." + "b".repeat(64)

    private fun parse(
        host: String = "192.168.1.23",
        port: String = "35001",
        token: String = pairing,
        request: String = "7E0",
        response: String = "7E8",
        profile: String = "33"
    ) = J2534BridgeConnectionForm.parse(
        host, port, token, request, response, profile
    )

    @Test fun validatedBridgeFormNeverPrintsTlsPairingSecret() {
        val configured = parse()
        val printed = configured.toString()
        assertTrue(printed.contains("REDACTED"))
        assertFalse(printed.contains(pairing))
        assertFalse(printed.contains("b".repeat(64)))
        assertEquals(pairing, configured.pairingCode)
    }

    @Test fun acceptsValidPinnedPrivateLan11And29BitForms() {
        val standard = parse()
        assertEquals("192.168.1.23", standard.host)
        assertEquals(35001, standard.port)
        assertEquals(0x7E0, standard.requestCanId)
        assertEquals(0x7E8, standard.responseCanId)
        assertFalse(standard.can29Bit)
        val extended = parse(
            host = "10.0.0.1",
            request = "18DAF110", response = "18DA10F1", profile = "34"
        )
        assertTrue(extended.can29Bit)
        assertEquals(0x18DAF110, extended.requestCanId)
    }

    @Test fun rejectsUnsafeHostAndMalformedPairingBeforeNetwork() {
        for (host in listOf(
            "", "localhost", "127.0.0.1", "8.8.8.8",
            "192.168.001.23", "192.168.1.999", "192.168.1.2.example.com",
            "172.15.0.1", "172.32.0.1", "192.168.1.2:35001"
        )) {
            assertTrue("Host $host was unexpectedly accepted", runCatching {
                parse(host = host)
            }.isFailure)
        }
        assertTrue(runCatching { parse(token = "old-token-only") }.isFailure)
        assertTrue(runCatching { parse(token = pairing.dropLast(1)) }.isFailure)
    }

    @Test fun rejectsInvalidPortAndMisconfiguredCanProfile() {
        for (port in listOf("0", "80", "65536", "not-a-port", "-1", "")) {
            assertTrue(runCatching { parse(port = port) }.isFailure)
        }
        assertTrue(runCatching { parse(request = "7E0", response = "7E0") }.isFailure)
        assertTrue(runCatching { parse(request = "7E0", response = "18DA10F1") }.isFailure)
        assertTrue(runCatching { parse(request = "FFF") }.isFailure)
        assertTrue(runCatching {
            parse(request = "FFFFFFFF", response = "18DA10F1", profile = "34")
        }.isFailure)
        assertTrue(runCatching { parse(profile = "34") }.isFailure)
        assertTrue(runCatching { parse(profile = "999") }.isFailure)
    }
    @Test fun pastedLineBreaksAndTabsCannotBypassBridgePairingPreflight() {
        for (input in listOf("\n$pairing", "$pairing\r", "$pairing\n", "\t$pairing")) {
            assertTrue(runCatching { parse(token = input) }.isFailure)
        }
        assertEquals(pairing, parse(token = "  $pairing  ").pairingCode)
    }

}
