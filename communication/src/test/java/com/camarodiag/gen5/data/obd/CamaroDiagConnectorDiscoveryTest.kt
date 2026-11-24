package com.camarodiag.gen5.data.obd

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CamaroDiagConnectorDiscoveryTest {
    private val ping = "PONG CamaroDiagConnectors 0.1.0"
    private val caps = "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled"
    private val status = "STATUS mode=prototype write=disabled"

    private class FakeChannel(
        private val responses: List<String>,
        private val chunkSize: Int = 3
    ) : CamaroDiagConnectorDiscovery.Channel {
        val sent = mutableListOf<String>()
        private var pending = byteArrayOf()
        override fun write(bytes: ByteArray) {
            check(pending.isEmpty())
            pending = responses[sent.size].toByteArray(Charsets.UTF_8)
            sent += bytes.toString(Charsets.US_ASCII)
        }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val count = minOf(buffer.size, pending.size, chunkSize)
            pending.copyInto(buffer, endIndex = count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }
    }

    @Test fun recognizesOnlyExistingReadOnlyPrototypeOverFragmentedUsb() {
        val c = FakeChannel(listOf("$ping\r\n", "$caps\r\n", "$status\r\n"))
        val report = CamaroDiagConnectorDiscovery.inspect(c)
        assertEquals("0.1.0", report.firmware)
        assertEquals(listOf(ping, caps, status), report.lines)
        assertEquals(listOf("PING\n", "CAPS\n", "STATUS\n"), c.sent)
    }

    @Test fun completeCapsPacketIsNotTruncatedToLegacySerialBuffer() {
        val c = FakeChannel(listOf("$ping\r\n", "$caps\r\n", "$status\r\n"), 512)
        assertEquals(listOf(ping, caps, status), CamaroDiagConnectorDiscovery.inspect(c).lines)
        assertEquals(listOf("PING\n", "CAPS\n", "STATUS\n"), c.sent)
        val trailing = FakeChannel(listOf("$ping\n", "$caps\nEXTRA\n"), 512)
        assertTrue(runCatching { CamaroDiagConnectorDiscovery.inspect(trailing) }.exceptionOrNull() is IOException)
        assertEquals(listOf("PING\n", "CAPS\n"), trailing.sent)
    }

    @Test fun slowDripUsbReplyCannotExtendDiscoveryDeadline() {
        val c = FakeChannel(listOf("$ping\n", "$caps\n", "$status\n"), chunkSize = 1)
        var clockNanos = 0L
        val error = runCatching {
            CamaroDiagConnectorDiscovery.inspectWithClock(c, clock = {
                clockNanos += 500_000_000L
                clockNanos
            })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(listOf("PING\n"), c.sent)
    }

    @Test fun slowUsbWriteConsumesTheSameThreeSecondDeadline() {
        var nanos = 0L
        var reads = 0
        var writes = 0
        val c = object : CamaroDiagConnectorDiscovery.Channel {
            override fun write(bytes: ByteArray) {
                writes++
                nanos = 3_000_000_000L
            }
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                reads++
                return 0
            }
        }
        val error = runCatching {
            CamaroDiagConnectorDiscovery.inspectWithClock(c, clock = { nanos })
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, writes)
        assertEquals(0, reads)
    }

    @Test fun replyArrivingExactlyAtDeadlineIsNotAccepted() {
        val c = FakeChannel(listOf("$ping\n"), chunkSize = 512)
        val times = listOf(0L, 0L, 3_000_000_000L).iterator()
        assertTrue(runCatching {
            CamaroDiagConnectorDiscovery.inspectWithClock(c, clock = { times.next() })
        }.exceptionOrNull() is IOException)
        assertEquals(listOf("PING\n"), c.sent)
    }

    @Test fun publicDiscoveryNeverAuthenticatesOrEnablesEcuTraffic() {
        val c = FakeChannel(listOf("$ping\n", "$caps\n", "$status\n"))
        val report = CamaroDiagConnectorDiscovery.inspect(c)
        assertFalse(report.authenticated)
        assertFalse(report.vehicleAccessAllowed)
        val capabilities = report.toAdapterCapabilities()
        assertEquals(com.camarodiag.gen5.domain.model.AdapterTier.DISCONNECTED, capabilities.tier)
        assertFalse(capabilities.supportsCanTransmit)
        assertFalse(capabilities.supportsCanHeaders)
        assertFalse(capabilities.supportsLongFrames)
        assertFalse(capabilities.supportsSwCan)
    }

    @Test fun explicitKklLabelsNeverEnterElmOrNativeDiscoveryByMistake() {
        val labels = listOf("FTDI KKL (#12)", "CamaroDiagConnectors K-Line (#123)",
            "FTDI ELM327 (#5)", "FT232R USB UART (#7)", "KKLegacy (#9)")
        assertTrue(CamaroDiagConnectorDiscovery.requiresLegacyKklIsolation(12, labels))
        assertTrue(CamaroDiagConnectorDiscovery.requiresLegacyKklIsolation(123, labels))
        for (id in listOf(1, 5, 7, 9, 23)) {
            assertFalse(CamaroDiagConnectorDiscovery.requiresLegacyKklIsolation(id, labels))
        }
        assertTrue(CamaroDiagConnectorDiscovery.isLegacyKklUsbLabel("KLINE"))
        assertTrue(CamaroDiagConnectorDiscovery.isLegacyKklUsbLabel("K Line"))
    }

    @Test fun namedConnectorCannotBeRoutedThroughLegacyUsbProbe() {
        val labels = listOf("ELM327 #12", "camarodiagconnectors #123", "CamaroDiagConnectors #5")
        assertTrue(CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(123, labels))
        assertTrue(CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(5, labels))
        assertFalse(CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(12, labels))
        assertFalse(CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(1, labels))
        assertFalse(CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(7, emptyList()))
    }

    @Test fun deviceIdAlwaysUsesTheFinalUsbSuffix() {
        assertEquals(17, CamaroDiagConnectorDiscovery.deviceIdFromLabel(
            "CamaroDiagConnectors (#999 embedded) (#17)"
        ))
        assertEquals(17, CamaroDiagConnectorDiscovery.deviceIdFromLabel(
            "CamaroDiagConnectors #999 internal (#17)"
        ))
        assertEquals(17, CamaroDiagConnectorDiscovery.deviceIdFromLabel(
            "CamaroDiagConnectors #17"
        ))
        assertEquals(null, CamaroDiagConnectorDiscovery.deviceIdFromLabel(
            "CamaroDiagConnectors (#17) trailing"
        ))
        assertEquals(null, CamaroDiagConnectorDiscovery.deviceIdFromLabel(
            "CamaroDiagConnectors (#999999999999999999999)"
        ))
        assertTrue(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(
            17, listOf("CamaroDiagConnectors #999 internal (#17)")
        ))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(
            999, listOf("CamaroDiagConnectors #999 internal (#17)")
        ))
    }

    @Test fun liveUsbDriverFamilyCannotSwitchBetweenElmAndIsolatedConnector() {
        val policy = CamaroDiagConnectorDiscovery::allowsUsbDriverFamily
        // Normal adapters may expose generic descriptors, while expressly
        // named connector/KKL devices must never be opened through ELM.
        assertTrue(policy("OBDLink USB", false))
        assertTrue(policy("FT232R USB UART", false))
        assertTrue(policy(null, false))
        assertFalse(policy("CamaroDiagConnectors CDC", false))
        assertFalse(policy("FTDI KKL", false))
        assertFalse(policy("CamaroDiagConnectors K-Line", false))
        // Discovery only requires a named isolated board; its public ID does
        // not authenticate firmware or enable any ECU service.
        assertTrue(policy("CamaroDiagConnectors CDC", true))
        assertFalse(policy("ELM327 USB", true))
        assertFalse(policy(null, true))
        assertFalse(policy("CamaroDiagConnectors K-Line", true))
    }

    @Test fun onlyEnumeratedNamedNonKklUsbBoardCanBeDiscovered() {
        val labels = listOf(
            "CamaroDiagConnectors (#17)",
            "FT232R generic ELM327 (#18)",
            "CamaroDiagConnectors K-Line (#19)",
            "FTDI KKL (#20)"
        )
        assertTrue(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(17, labels))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(18, labels))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(19, labels))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(20, labels))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(21, labels))
        assertFalse(CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(17, emptyList()))
        // Legacy routing cannot grant ECU permission even after a valid discovery.
        assertFalse(CamaroDiagConnectorDiscovery.Report("0.1.0", emptyList()).vehicleAccessAllowed)
    }

    @Test fun unknownIdentityAndFutureVersionsCannotEnterLegacyDiscovery() {
        for (response in listOf("ELM327 v1.5", "PONG CamaroDiagConnectors 1.0.0", "PONG Other 0.1.0")) {
            val c = FakeChannel(listOf("$response\n"))
            assertTrue(runCatching { CamaroDiagConnectorDiscovery.inspect(c) }.exceptionOrNull() is IOException)
            assertEquals(listOf("PING\n"), c.sent)
        }
    }

    @Test fun malformedAndEnabledCapabilitiesAreRejectedRatherThanActivatingHardware() {
        val replies = listOf(
            caps.replace("hs_can=disabled", "hs_can=enabled"),
            caps.replace("hs_can=disabled sw_can=disabled", "sw_can=disabled hs_can=disabled"),
            caps.replace(" hs_can=disabled", "  hs_can=disabled"),
            caps.replace("ecu_flash=disabled", "ecu_flash=enabled"),
            caps.replace("j2534=disabled", "hs_can=disabled"),
            caps.replace(" j2534=disabled", ""),
            "$caps jtag=disabled",
            "$caps\rjunk",
            caps.replace("disabled", "désactivé"),
            "x".repeat(300)
        )
        for (response in replies) {
            val c = FakeChannel(listOf("$ping\n", "$response\n"))
            assertTrue(response, runCatching {
                CamaroDiagConnectorDiscovery.inspect(c)
            }.exceptionOrNull() is IOException)
            assertEquals(listOf("PING\n", "CAPS\n"), c.sent)
        }
    }

    @Test fun unsafeStatusOrExtraDataDoesNotReturnReport() {
        val c = FakeChannel(listOf("$ping\n", "$caps\n", "STATUS mode=prototype write=enabled\n"))
        assertTrue(runCatching { CamaroDiagConnectorDiscovery.inspect(c) }.exceptionOrNull() is IOException)
        val extra = FakeChannel(listOf("$ping\nEXTRA\n"), 64)
        assertTrue(runCatching { CamaroDiagConnectorDiscovery.inspect(extra) }.exceptionOrNull() is IOException)
    }

    @Test fun cancellationStopsBeforeSendingAnyCommand() {
        val c = FakeChannel(emptyList())
        val error = runCatching {
            CamaroDiagConnectorDiscovery.inspect(c) { throw java.util.concurrent.CancellationException() }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(c.sent.isEmpty())
    }
}
