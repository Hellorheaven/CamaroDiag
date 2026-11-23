package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterConnectionFailurePolicyTest {
    @Test fun permissionHandoffIsNotMisrepresentedAsTransportFailure() {
        assertEquals(
            AdapterConnectionFailurePolicy.Kind.USB_PERMISSION_REQUIRED,
            AdapterConnectionFailurePolicy.classify(UsbPermissionPendingException())
        )
    }

    @Test fun ordinaryTransportAndSecurityErrorsRemainFailures() {
        assertEquals(
            AdapterConnectionFailurePolicy.Kind.CONNECTION_FAILED,
            AdapterConnectionFailurePolicy.classify(java.io.IOException("USB disconnected"))
        )
        assertEquals(
            AdapterConnectionFailurePolicy.Kind.CONNECTION_FAILED,
            AdapterConnectionFailurePolicy.classify(SecurityException("USB denied"))
        )
    }
}
