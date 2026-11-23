package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbPermissionReconnectPolicyTest {
    @Test fun resumesOnlyExplicitlySelectedEnumeratedUsbDevice() {
        val chosen = "OBDLink USB (#17)"
        val pending = UsbPermissionReconnectPolicy.forSelection("usb", chosen, 52L)
        assertEquals(17, pending?.deviceId)
        assertTrue(UsbPermissionReconnectPolicy.mayResume(
            pending, 17, true, 52L, listOf(chosen, "Other USB (#18)")
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            pending, 18, true, 52L, listOf(chosen, "Other USB (#18)")
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            pending, 17, false, 52L, listOf(chosen)
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            pending, 17, true, 53L, listOf(chosen)
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            pending, 17, true, 52L, listOf("Replacement device (#17)")
        ))
    }

    @Test fun connectorRetryKeepsChosenDiscoveryModeAndKklIsolation() {
        val label = "CamaroDiagConnectors CDC (#42)"
        val selected = UsbPermissionReconnectPolicy.forSelection(
            "connector_usb_binary", label, 99L
        )
        assertTrue(UsbPermissionReconnectPolicy.mayResume(
            selected, 42, true, 99L, listOf(label)
        ))
        assertEquals("connector_usb_binary", selected?.transport)
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            selected, 42, true, 99L, listOf("KKL CamaroDiagConnectors CDC (#42)")
        ))
        assertNull(UsbPermissionReconnectPolicy.forSelection(
            "j2534", "CamaroDiagConnectors CDC (#42)", 99L
        ))
    }

    @Test fun routesPermissionOnlyToTheOriginallySelectedAdapterFamily() {
        val connector = "CamaroDiagConnectors CDC (#42)"
        val elm = "OBDLink USB (#17)"
        val kkl = "KKL USB (#19)"
        assertNull(UsbPermissionReconnectPolicy.forSelection("usb", connector, 1L))
        assertNull(UsbPermissionReconnectPolicy.forSelection("usb", kkl, 1L))
        assertNull(UsbPermissionReconnectPolicy.forSelection("connector_usb", elm, 1L))
        assertNull(UsbPermissionReconnectPolicy.forSelection("connector_usb_binary", kkl, 1L))
        assertTrue(UsbPermissionReconnectPolicy.forSelection("usb", elm, 1L) != null)
        assertTrue(UsbPermissionReconnectPolicy.forSelection("connector_usb", connector, 1L) != null)
    }

    @Test fun refusesAmbiguousEnumerationAndMismatchedPendingType() {
        val elm = "OBDLink USB (#17)"
        val selected = UsbPermissionReconnectPolicy.forSelection("usb", elm, 6L)
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            selected, 17, true, 6L, listOf(elm, "Different serial interface (#17)")
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            selected, 17, true, 6L, listOf(elm, "CamaroDiagConnectors (#17)")
        ))
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            selected, 17, true, 6L, listOf(elm, "KKL USB (#17)")
        ))
        val connector = UsbPermissionReconnectPolicy.forSelection(
            "connector_usb_binary", "CamaroDiagConnectors CDC (#42)", 7L
        )
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            connector, 42, true, 7L,
            listOf("CamaroDiagConnectors CDC (#42)", "Other USB (#42)")
        ))
    }

    @Test fun usbProductNameCannotSpoofTheDeviceId() {
        val label = "OBD #5 other (#17)"
        val pending = UsbPermissionReconnectPolicy.forSelection("usb", label, 4L)
        assertEquals(17, pending?.deviceId)
        assertFalse(UsbPermissionReconnectPolicy.mayResume(
            pending, 5, true, 4L, listOf(label)
        ))
        assertNull(UsbPermissionReconnectPolicy.forSelection("usb", "bad USB label", 4L))
    }
}
