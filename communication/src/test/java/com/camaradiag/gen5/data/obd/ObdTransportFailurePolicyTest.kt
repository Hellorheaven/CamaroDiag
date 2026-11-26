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
    fun timedOutNativeWriteExplicitlyRetiresTheSocketButNotItsReplacement() {
        val timeout = SocketTimeoutException("native write timeout")
        val closed = ObdTransportDisconnectedException("socket retired", timeout)
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(closed))
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(IllegalStateException("wrapped", closed)))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(ObdSupersededSessionException(closed)))
    }

    @Test
    fun wrappedIoFailureStillReportsPhysicalDisconnect() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            IllegalStateException("transport operation failed", EOFException("peer closed"))
        ))
    }

    @Test
    fun staleSessionIoFailureDoesNotDisconnectNewAdapter() {
        val oldFailure = ObdSupersededSessionException(
            EOFException("Old adapter closed after reconnect")
        )
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(oldFailure))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            IOException("wrapped stale operation", oldFailure)
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
    @Test
    fun canAdapterRestorationFailureMustCloseTheVisibleSession() {
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(CanAdapterRestorationException()))
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            IllegalStateException("wrapped recovery failure", CanAdapterRestorationException())
        ))
    }

    @Test
    fun supersededSessionStillCannotCloseReplacementAdapter() {
        val stale = ObdSupersededSessionException(CanAdapterRestorationException())
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(stale))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            IllegalStateException("old session", stale)
        ))
    }

    @Test
    fun localCanArchiveFailureDoesNotMasqueradeAsVehicleDisconnect() {
        val storage = CanCaptureArchiveWriteException(IOException("device storage full"))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(storage))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            IllegalStateException("failed to capture CAN", storage)
        ))
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            IOException("actual USB transport failure")
        ))
    }

    @Test
    fun failedCanExchangePreservesCauseClassificationAndSessionIsolation() {
        val noFrames = emptyList<CanConsoleTimedRecord>()
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            CanConsolePhysicalExchangeException(
                noFrames, SocketTimeoutException("prompt timeout")
            )
        ))
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            CanConsolePhysicalExchangeException(
                noFrames, EOFException("USB adapter disconnected")
            )
        ))
        assertTrue(ObdTransportFailurePolicy.isConnectionLoss(
            CanConsolePhysicalExchangeException(
                noFrames, CanAdapterRestorationException("WIFI")
            )
        ))
        assertFalse(ObdTransportFailurePolicy.isConnectionLoss(
            CanConsolePhysicalExchangeException(
                noFrames, ObdSupersededSessionException(EOFException("old adapter"))
            )
        ))
    }

    @Test
    fun adapterRestoreErrorPreservesTheOriginalPhysicalTransportForTheUi() {
        for (kind in listOf("USB", "WIFI", "BLE", "BLUETOOTH")) {
            val error = CanAdapterRestorationException(kind)
            assertTrue(ObdTransportFailurePolicy.isConnectionLoss(error))
            assertTrue(error.originalTransportKind == kind)
        }
    }

}
