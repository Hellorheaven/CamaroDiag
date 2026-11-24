
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

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: android.hardware.usb.UsbDeviceConnection? = null
    @Volatile private var connectionLost = false

    val isConnected: Boolean
        get() = port != null && connection != null && !connectionLost

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
        close()
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
            // Device IDs may be reused after hot-unplug. Recheck the actual
            // descriptor immediately before requesting permission/opening USB.
            // A dedicated prototype cannot masquerade as a normal ELM device.
            if (!CamaroDiagConnectorDiscovery.allowsUsbDriverFamily(
                    driver.device.productName, discoveryOnly
                )
            ) {
                return@withContext Result.failure(
                    IllegalArgumentException("The selected USB adapter family changed")
                )
            }

            // Connectors' binary prototype supports one unambiguous CDC ACM
            // channel. Never select port zero from an unknown multiport device.
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
                    if (Build.VERSION.SDK_INT >= 31) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }

                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    deviceId,
                    intent,
                    flags
                )

                usbManager.requestPermission(driver.device, permissionIntent)

                return@withContext Result.failure(
                    UsbPermissionPendingException()
                )
            }

            val conn = usbManager.openDevice(driver.device)
                ?: return@withContext Result.failure(
                    IllegalStateException("Unable to open USB OBD device")
                )
            // Track ownership before subsequent setup so every failure releases
            // the USB device handle, including missing-port and serial-open errors.
            connection = conn

            val serial = driver.ports.firstOrNull() ?: run {
                close()
                return@withContext Result.failure<Unit>(
                    IllegalStateException("No USB serial port")
                )
            }

            port = serial
            serial.open(conn)
            serial.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            connectionLost = false

            Result.success(Unit)

        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A superseded connection must finish closing its handle before
            // the next adapter handshake can begin.
            close()
            throw cancelled
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    fun write(data: ByteArray) {
        if (connectionLost) throw ObdTransportDisconnectedException("USB OBD adapter disconnected")
        val activePort = port ?: throw ObdTransportDisconnectedException("USB OBD adapter is not connected")
        try {
            activePort.write(data, 1500)
        } catch (error: java.io.IOException) {
            if (port === activePort) connectionLost = true
            throw error
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (connectionLost) throw ObdTransportDisconnectedException("USB OBD adapter disconnected")
        val activePort = port ?: run {
            connectionLost = true
            return -1
        }
        return try {
            activePort.read(buffer, timeoutMs).also { count ->
                if (count < 0 && port === activePort) connectionLost = true
            }
        } catch (error: java.io.IOException) {
            if (port === activePort) connectionLost = true
            throw error
        }
    }

    fun close() {
        try {
            port?.close()
        } catch (_: Exception) {
        }

        try {
            connection?.close()
        } catch (_: Exception) {
        }

        port = null
        connection = null
        connectionLost = false
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
class ElmWifiTransport {

    @Volatile private var socket: Socket? = null
    @Volatile private var connectionLost = false

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed && !it.isInputShutdown && !it.isOutputShutdown && !connectionLost } == true

    suspend fun connect(
        host: String,
        port: Int = 35000,
        timeoutMs: Int = 4000
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            // Socket.connect(..., 0) means an unbounded wait; reject it before
            // even creating a TCP socket. Disconnection must stay responsive.
            ElmWifiConnectionPolicy.validate(host, port, timeoutMs)
            val s = Socket()
            // Publish the socket before connect so a failed attempt can close it.
            socket = s
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = 1200
            connectionLost = false

            Result.success(Unit)

        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // A superseded connection must finish closing its handle before
            // the next adapter handshake can begin.
            close()
            throw cancelled
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    fun write(data: ByteArray) {
        if (connectionLost) throw ObdTransportDisconnectedException("Wi-Fi OBD adapter disconnected")
        val activeSocket = socket ?: throw ObdTransportDisconnectedException("Wi-Fi OBD adapter is not connected")
        try {
            activeSocket.getOutputStream().write(data)
        } catch (error: java.io.IOException) {
            if (socket === activeSocket) connectionLost = true
            throw error
        }
    }

    fun read(buffer: ByteArray): Int {
        if (connectionLost) throw ObdTransportDisconnectedException("Wi-Fi OBD adapter disconnected")
        val activeSocket = socket ?: run {
            connectionLost = true
            return -1
        }
        return try {
            activeSocket.getInputStream().read(buffer).also { count ->
                if (count < 0 && socket === activeSocket) connectionLost = true
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            // A read timeout is normal while waiting for the ELM prompt.
            throw timeout
        } catch (error: java.io.IOException) {
            if (socket === activeSocket) connectionLost = true
            throw error
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        connectionLost = false
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
