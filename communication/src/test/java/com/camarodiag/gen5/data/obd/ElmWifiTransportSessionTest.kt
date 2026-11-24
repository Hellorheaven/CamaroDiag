package com.camarodiag.gen5.data.obd

import java.net.Socket
import java.net.SocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No TCP port, adapter or vehicle is opened in these connection-race tests. */
class ElmWifiTransportSessionTest {
    private class FakeSocket(
        private val blockConnect: Boolean = false,
        private val failAfterWait: Boolean = false,
        private val blockRead: Boolean = false,
        private val blockWrite: Boolean = false
    ) : Socket() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val writeReleased = CountDownLatch(1)
        @Volatile var fakeClosed = false
        @Volatile var connected = false

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            entered.countDown()
            if (blockConnect) {
                check(released.await(5, TimeUnit.SECONDS)) {
                    "Simulated connect was not released by the test"
                }
            }
            if (failAfterWait) throw java.io.IOException("Simulated old handshake failure")
            connected = true
        }
        override fun setTcpNoDelay(on: Boolean) {}
        override fun setSoTimeout(timeout: Int) {}
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                readEntered.countDown()
                if (blockRead) check(readReleased.await(5, TimeUnit.SECONDS))
                return 0x42
            }
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                readEntered.countDown()
                if (blockRead) check(readReleased.await(5, TimeUnit.SECONDS))
                bytes[off] = 0x42
                return 1
            }
        }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writeEntered.countDown()
                if (blockWrite) check(writeReleased.await(5, TimeUnit.SECONDS))
            }
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                writeEntered.countDown()
                if (blockWrite) check(writeReleased.await(5, TimeUnit.SECONDS))
            }
        }
        override fun isConnected(): Boolean = connected
        override fun isClosed(): Boolean = fakeClosed
        override fun isInputShutdown(): Boolean = false
        override fun isOutputShutdown(): Boolean = false
        override fun close() {
            fakeClosed = true
            super.close()
        }
    }

    @Test fun aSupersededWifiConnectCannotReplaceOrCloseTheNewAdapter() = runBlocking {
        val old = FakeSocket(blockConnect = true)
        val current = FakeSocket()
        val sockets = ArrayDeque(listOf(old, current))
        val transport = ElmWifiTransport { synchronized(sockets) { sockets.removeFirst() } }
        try {
            val oldConnect = async(Dispatchers.IO) {
                transport.connect("192.168.0.10").isSuccess
            }
            assertTrue(old.entered.await(5, TimeUnit.SECONDS))
            assertTrue(transport.connect("192.168.0.11").isSuccess)
            assertTrue(transport.isConnected)
            assertTrue(old.fakeClosed)

            old.released.countDown()
            assertFalse(oldConnect.await())
            assertTrue(transport.isConnected)
            assertFalse(current.fakeClosed)
        } finally {
            old.released.countDown()
            transport.close()
        }
        assertTrue(current.fakeClosed)
        Unit
    }

    @Test fun lateFailureFromOldWifiHandshakeDoesNotCloseNewSocket() = runBlocking {
        val old = FakeSocket(blockConnect = true, failAfterWait = true)
        val current = FakeSocket()
        val sockets = ArrayDeque(listOf(old, current))
        val transport = ElmWifiTransport { synchronized(sockets) { sockets.removeFirst() } }
        try {
            val oldConnect = async(Dispatchers.IO) { transport.connect("192.168.0.10") }
            assertTrue(old.entered.await(5, TimeUnit.SECONDS))
            assertTrue(transport.connect("192.168.0.11").isSuccess)
            old.released.countDown()
            assertTrue(oldConnect.await().isFailure)
            assertTrue(transport.isConnected)
            assertFalse(current.fakeClosed)
        } finally {
            old.released.countDown()
            transport.close()
        }
        Unit
    }

    @Test fun lateWifiReadFromReplacedSocketIsNeverReturnedAsCurrentData() = runBlocking {
        val old = FakeSocket(blockRead = true)
        val current = FakeSocket()
        val sockets = ArrayDeque(listOf(old, current))
        val transport = ElmWifiTransport { synchronized(sockets) { sockets.removeFirst() } }
        try {
            assertTrue(transport.connect("192.168.0.10").isSuccess)
            val stale = async(Dispatchers.IO) {
                runCatching { transport.read(ByteArray(8)) }.exceptionOrNull()
            }
            assertTrue(old.readEntered.await(5, TimeUnit.SECONDS))
            assertTrue(transport.connect("192.168.0.11").isSuccess)
            old.readReleased.countDown()
            assertTrue(stale.await() is ObdSupersededSessionException)
            assertTrue(transport.isConnected)
            assertFalse(current.fakeClosed)
        } finally {
            old.readReleased.countDown()
            transport.close()
        }
        Unit
    }

    @Test fun lateWifiWriteFromReplacedSocketCannotReportSuccess() = runBlocking {
        val old = FakeSocket(blockWrite = true)
        val current = FakeSocket()
        val sockets = ArrayDeque(listOf(old, current))
        val transport = ElmWifiTransport { synchronized(sockets) { sockets.removeFirst() } }
        try {
            assertTrue(transport.connect("192.168.0.10").isSuccess)
            val stale = async(Dispatchers.IO) {
                runCatching { transport.write(byteArrayOf(0x41)) }.exceptionOrNull()
            }
            assertTrue(old.writeEntered.await(5, TimeUnit.SECONDS))
            assertTrue(transport.connect("192.168.0.11").isSuccess)
            old.writeReleased.countDown()
            assertTrue(stale.await() is ObdSupersededSessionException)
            assertTrue(transport.isConnected)
            assertFalse(current.fakeClosed)
        } finally {
            old.writeReleased.countDown()
            transport.close()
        }
        Unit
    }

    @Test fun failedSocketAllocationPreservesActiveWifiAdapter() = runBlocking {
        val active = FakeSocket()
        var calls = 0
        val transport = ElmWifiTransport {
            if (++calls == 1) active
            else throw java.io.IOException("Simulated socket allocation failure")
        }
        try {
            assertTrue(transport.connect("192.168.0.10").isSuccess)
            val failed = transport.connect("192.168.0.11")
            assertTrue(failed.isFailure)
            assertTrue(transport.isConnected)
            assertFalse(active.fakeClosed)
        } finally {
            transport.close()
        }
        Unit
    }

    @Test fun invalidNewWifiParametersDoNotDisconnectCurrentSession() = runBlocking {
        val active = FakeSocket()
        val transport = ElmWifiTransport { active }
        try {
            assertTrue(transport.connect("192.168.0.10").isSuccess)
            assertTrue(transport.isConnected)
            // Invalid input is rejected before any replacement socket is created.
            val rejected = transport.connect("bad host", 35000, 4000)
            assertTrue(rejected.isFailure)
            assertTrue(transport.isConnected)
            assertFalse(active.fakeClosed)
        } finally {
            transport.close()
        }
        Unit
    }
}
