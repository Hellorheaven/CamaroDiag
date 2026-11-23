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
