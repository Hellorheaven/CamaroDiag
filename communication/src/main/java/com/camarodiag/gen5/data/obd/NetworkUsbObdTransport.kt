package com.camarodiag.gen5.data.obd

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** USB serial ELM/STN transport. Device permission is requested by Android when opened. */
class ElmUsbTransport(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null

    fun availableDevices(): List<String> = UsbSerialProber.getDefaultProber()
        .findAllDrivers(usbManager).map { "${it.device.productName ?: "USB OBD"} (#${it.device.deviceId})" }

    suspend fun connect(deviceId: Int, baudRate: Int = 38400): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device.deviceId == deviceId }
                ?: return@withContext Result.failure(IllegalArgumentException("USB serial OBD device not found"))
            if (!usbManager.hasPermission(driver.device)) {
                val intent = Intent(UsbPermissionReceiver.ACTION).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val permissionIntent = PendingIntent.getBroadcast(context, deviceId, intent, flags)
                usbManager.requestPermission(driver.device, permissionIntent)
                return@withContext Result.failure(SecurityException("Autorisation USB demandée. Validez la fenêtre Android puis reconnectez l'adaptateur."))
            }
            val conn = usbManager.openDevice(driver.device)
                ?: return@withContext Result.failure(IllegalStateException("Unable to open USB OBD device"))
            val serial = driver.ports.firstOrNull()
                ?: return@withContext Result.failure(IllegalStateException("No USB serial port"))
            serial.open(conn)
            serial.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            connection = conn
            port = serial
            Result.success(Unit)
        } catch (e: Exception) { close(); Result.failure(e) }
    }

    fun write(data: ByteArray) { port?.write(data, 1500) ?: error("USB transport not connected") }
    fun read(buffer: ByteArray, timeoutMs: Int): Int = port?.read(buffer, timeoutMs) ?: -1
    fun close() { try { port?.close() } catch (_: Exception) {}; try { connection?.close() } catch (_: Exception) {}; port = null; connection = null }
}

/** TCP transport for Wi-Fi ELM327 adapters; common defaults are configurable by the caller. */
class ElmWifiTransport {
    private var socket: Socket? = null
    suspend fun connect(host: String, port: Int = 35000, timeoutMs: Int = 4000): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 1200
            socket = s
            Result.success(Unit)
        } catch (e: Exception) { close(); Result.failure(e) }
    }
    fun write(data: ByteArray) { socket?.getOutputStream()?.write(data) ?: error("Wi-Fi transport not connected") }
    fun read(buffer: ByteArray): Int = socket?.getInputStream()?.read(buffer) ?: -1
    fun close() { try { socket?.close() } catch (_: Exception) {}; socket = null }
}


class UsbPermissionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { /* Android USB permission result is reflected by UsbManager.hasPermission(). */ }
    companion object { const val ACTION = "com.camarodiag.gen5.USB_PERMISSION" }
}

/** Extension point for vendor-specific J2534/VCX implementations. No generic J2534 Android API exists. */
interface VxdiagAndroidProvider {
    val modelName: String
    suspend fun open(): Result<Unit>
    suspend fun exchange(request: ByteArray): Result<ByteArray>
    fun close()
}
