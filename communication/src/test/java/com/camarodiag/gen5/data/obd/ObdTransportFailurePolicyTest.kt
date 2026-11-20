package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

class ObdTransportFailurePolicyTest {
    @Test
    fun explicitTransportDisconnectIsAConnectionLoss() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            ObdTransportDisconnectedException("BLE disconnected")
        ))
    }

    @Test
    fun endOfStreamIsAConnectionLoss() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(EOFException("USB disconnected")))
    }

    @Test
    fun commandResponseTimeoutDoesNotImplyPhysicalDisconnect() {
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            SocketTimeoutException("ELM prompt not received")
        ))
    }

    @Test
    fun wrappedSocketTimeoutDoesNotImplyPhysicalDisconnect() {
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            IOException("command read failed", SocketTimeoutException("prompt timeout"))
        ))
    }

    @Test
    fun wrappedIoFailureStillReportsPhysicalDisconnect() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            IllegalStateException("transport operation failed", EOFException("peer closed"))
        ))
    }

    @Test
    fun nonIoFailureDoesNotImplyPhysicalDisconnect() {
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(IllegalStateException("bad command")))
    }

    @Test
    fun genericIoFailureIsTreatedAsTransportLoss() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(IOException("socket closed")))
    }
}
