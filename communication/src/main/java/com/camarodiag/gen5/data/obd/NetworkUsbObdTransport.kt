
package com.camarodiag.gen5.data.obd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** The Android USB permission dialog is pending; this is not a failed handshake. */
class UsbPermissionPendingException : SecurityException("USB device permission requested")

/** USB serial ELM/STN transport. Device permission is requested by Android when opened. */
class ElmUsbTransport(private val context: Context) {
    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private class UsbSession(
        val port: UsbSerialPort,
        val connection: android.hardware.usb.UsbDeviceConnection
    ) {
        @Volatile var lost = false
        fun close() {
            try { port.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
        }
    }

    // The USB open/permission path can be suspended or blocked while the user
    // selects another adapter. Only the newest generation owns the USB handle.
    private val sessions = ObdConnectionSlot<UsbSession>()

    val isConnected: Boolean
        get() = sessions.snapshot()?.lost == false

    fun availableDevices(): List<String> =
        UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .map {
                "${it.device.productName ?: "USB OBD"} (#${it.device.deviceId})"
            }

    suspend fun connect(
        deviceId: Int,
        baudRate: Int = 38400,
        discoveryOnly: Boolean = false,
        requireCdc: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val selection = sessions.begin()
        selection.previous?.close()
        var candidate: UsbSession? = null
        try {
            val candidates = UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .filter { it.device.deviceId == deviceId }
            if (candidates.size != 1) {
                return@withContext Result.failure(
                    IllegalArgumentException("USB serial adapter is missing or ambiguously enumerated")
                )
            }
            val driver = candidates.single()
            // A hot-plugged connector cannot be mistaken for an ELM channel.
            // The product name remains a routing hint, not authentication.
            if (!CamaroDiagConnectorDiscovery.allowsUsbDriverFamily(
                    driver.device.productName, discoveryOnly
                )
            ) {
                return@withContext Result.failure(
                    IllegalArgumentException("The selected USB adapter family changed")
                )
            }
            if ((discoveryOnly && driver.ports.size != 1) ||
                (requireCdc && driver !is CdcAcmSerialDriver)
            ) {
                return@withContext Result.failure(
                    IllegalArgumentException("Unsupported or ambiguous connector USB interface")
                )
            }

            if (!usbManager.hasPermission(driver.device)) {
                val intent = Intent(UsbPermissionReceiver.ACTION)
                    .setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = PendingIntent.getBroadcast(
                    context, deviceId, intent, flags
                )
                usbManager.requestPermission(driver.device, permissionIntent)
                return@withContext Result.failure(UsbPermissionPendingException())
            }

            val conn = usbManager.openDevice(driver.device)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to open USB OBD device")
                )
            val serial = driver.ports.firstOrNull() ?: run {
                conn.close()
                return@withContext Result.failure<Unit>(
                    IllegalStateException("No USB serial port")
                )
            }
            val local = UsbSession(serial, conn)
            candidate = local
            serial.open(conn)
            serial.setParameters(
                baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
            // Do not publish a partially opened port or an obsolete USB ID.
            if (!sessions.publish(selection.generation, local)) {
                throw ObdSupersededSessionException(
                    java.io.IOException("A newer USB adapter selection replaced this open")
                )
            }
            candidate = null // Ownership transferred atomically to the active slot.
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            sessions.releaseIfCurrent(selection.generation)?.close()
            throw cancelled
        } catch (error: Exception) {
            sessions.releaseIfCurrent(selection.generation)?.close()
            Result.failure(error)
        } finally {
            // A stale attempt can close only the handle it opened itself.
            candidate?.close()
        }
    }

    fun write(data: ByteArray) {
        UsbSerialWritePolicy.requireNonEmpty(data)
        val active = sessions.snapshot()
            ?: throw ObdTransportDisconnectedException("USB OBD adapter is not connected")
        if (active.lost) throw ObdTransportDisconnectedException("USB OBD adapter disconnected")
        try {
            // usb-serial-for-android's UsbSerialPort.write returns Unit, not a
            // byte count. It either completes its internal write loop or
            // throws. Never invent byte-count or bus-delivery evidence.
            active.port.write(data, 1500)
            if (!sessions.isCurrent(active)) {
                throw java.io.IOException("USB adapter replaced during serial write")
            }
        } catch (error: java.io.IOException) {
            if (!sessions.isCurrent(active)) throw ObdSupersededSessionException(error)
            active.lost = true
            throw error
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        require(timeoutMs in 1..30_000) { "USB read timeout must be finite and positive" }
        val active = sessions.snapshot() ?: return -1
        if (active.lost) throw ObdTransportDisconnectedException("USB OBD adapter disconnected")
        return try {
            val count = active.port.read(buffer, timeoutMs)
            if (!sessions.isCurrent(active)) {
                throw java.io.IOException("USB adapter replaced during serial read")
            }
            if (count < 0) active.lost = true
            count
        } catch (error: java.io.IOException) {
            if (!sessions.isCurrent(active)) throw ObdSupersededSessionException(error)
            active.lost = true
            throw error
        }
    }

    fun close() {
        sessions.release()?.close()
    }
}

/**
 * An explicit zero socket timeout means "wait forever" in Java and is not
 * appropriate for a removable automotive adapter. Keep hostnames and IPv6
 * available; this is transport parameter validation, not an ECU write policy.
 */
internal object ElmWifiConnectionPolicy {
    fun validate(host: String, port: Int, timeoutMs: Int) {
        require(host.isNotBlank() && host.length <= 253 && host.none {
            it.isWhitespace() || it.isISOControl()
        }) { "Invalid Wi-Fi OBD adapter address" }
        require(port in 1..65535) { "Invalid Wi-Fi OBD adapter port" }
        require(timeoutMs in 1..30_000) {
            "Wi-Fi OBD connection timeout must be bounded and positive"
        }
    }
}

/** TCP transport for Wi-Fi ELM327 adapters; common defaults are configurable by the caller. */
class ElmWifiTransport(
    private val socketFactory: () -> Socket = { Socket() }
) {
    private val sessionLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var connectionLost = true

    val isConnected: Boolean
        get() = socket?.let {
            it.isConnected && !it.isClosed && !it.isInputShutdown &&
                !it.isOutputShutdown && !connectionLost
        } == true

    /**
     * Closing an expired socket must never close the *replacement* connection.
     * Socket.connect is blocking even though connect() runs on Dispatchers.IO,
     * so a second adapter selection can overtake the first handshake.
     */
    private fun retire(expected: Socket) {
        synchronized(sessionLock) {
            if (socket === expected) {
                socket = null
                connectionLost = true
            }
        }
        try { expected.close() } catch (_: Exception) {}
    }

    suspend fun connect(
        host: String,
        port: Int = 35000,
        timeoutMs: Int = 4000
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Invalid inputs must not replace an already working adapter.
        // Preserve the transport's Result contract rather than throwing
        // a validation exception across its public suspend API.
        try {
            ElmWifiConnectionPolicy.validate(host, port, timeoutMs)
        } catch (error: IllegalArgumentException) {
            return@withContext Result.failure(error)
        }
        val candidate = try {
            socketFactory()
        } catch (error: Exception) {
            // Failure to allocate a replacement cannot invalidate the previous socket.
            return@withContext Result.failure(error)
        }
        val previous = synchronized(sessionLock) {
            val previous = socket
            socket = candidate
            connectionLost = true
            previous
        }
        try { previous?.close() } catch (_: Exception) {}
        try {
            candidate.connect(InetSocketAddress(host, port), timeoutMs)
            candidate.tcpNoDelay = true
            candidate.soTimeout = 1200
            synchronized(sessionLock) {
                check(socket === candidate) {
                    "Wi-Fi OBD connection attempt was superseded."
                }
                connectionLost = false
            }
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            retire(candidate)
            throw cancelled
        } catch (e: Exception) {
            retire(candidate)
            Result.failure(e)
        }
    }

    private fun classifyIoFailure(active: Socket, error: java.io.IOException): java.io.IOException =
        synchronized(sessionLock) {
            if (socket !== active) ObdSupersededSessionException(error)
            else {
                connectionLost = true
                error
            }
        }

    fun write(data: ByteArray) {
        val activeSocket = synchronized(sessionLock) {
            if (connectionLost) throw ObdTransportDisconnectedException("Wi-Fi OBD adapter disconnected")
            socket ?: throw ObdTransportDisconnectedException("Wi-Fi OBD adapter is not connected")
        }
        try {
            activeSocket.getOutputStream().write(data)
            synchronized(sessionLock) {
                if (socket !== activeSocket)
                    throw java.io.IOException("Wi-Fi OBD connection replaced during write")
            }
        } catch (error: java.io.IOException) {
            throw classifyIoFailure(activeSocket, error)
        }
    }

    fun read(buffer: ByteArray): Int {
        val activeSocket = synchronized(sessionLock) {
            if (connectionLost) throw ObdTransportDisconnectedException("Wi-Fi OBD adapter disconnected")
            socket
        } ?: return -1
        return try {
            val count = activeSocket.getInputStream().read(buffer)
            synchronized(sessionLock) {
                if (socket !== activeSocket) {
                    throw java.io.IOException("Wi-Fi OBD connection replaced during read")
                }
                if (count < 0) connectionLost = true
            }
            count
        } catch (timeout: java.net.SocketTimeoutException) {
            // A normal ELM read gap is not a lost connection; a timeout from
            // an obsolete socket is a superseded-session error instead.
            synchronized(sessionLock) {
                if (socket !== activeSocket) throw ObdSupersededSessionException(timeout)
            }
            throw timeout
        } catch (error: java.io.IOException) {
            throw classifyIoFailure(activeSocket, error)
        }
    }

    fun close() {
        val previous = synchronized(sessionLock) {
            val previous = socket
            socket = null
            connectionLost = true
            previous
        }
        try { previous?.close() } catch (_: Exception) {}
    }
}

class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
        } ?: return
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // A positive intent flag alone is not evidence of permission: verify
        // the actual Android grant before any automatic retry can be offered.
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
            manager.hasPermission(device)
        UsbPermissionResultBus.publish(device.deviceId, granted)
    }

    companion object {
        const val ACTION = "com.camarodiag.gen5.USB_PERMISSION"
    }
}
