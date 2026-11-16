package com.camarodiag.gen5.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.camarodiag.gen5.domain.model.*
import com.camarodiag.gen5.data.obd.Obd2FeatureEngine
import com.camarodiag.gen5.data.obd.ObdCommandChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothObdManager(context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private val commandMutex = Mutex()
    private val usbTransport = ElmUsbTransport(context)
    private val wifiTransport = ElmWifiTransport()
    private val bleTransport = OBDLinkBleTransport(context)
    private var j2534BridgeTransport: J2534BridgeTransport? = null
    private var activeTransport: String = "BLUETOOTH"
    private var activeAdapterCapabilities: AdapterCapabilities? = null

    fun getUsbDevices(): List<String> = usbTransport.availableDevices()

    var isDemoMode: Boolean = true
        private set

    /** True only after the user explicitly selects a simulated adapter profile. */
    var isSimulationActive: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<Pair<String, String>> {
        return try {
            bluetoothAdapter?.bondedDevices
                ?.map { (it.name ?: "Unknown OBD Device") to it.address }
                ?.sortedByDescending { it.first.contains("OBD", true) || it.first.contains("ELM", true) || it.first.contains("vLinker", true) }
                ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Scans nearby BLE OBD adapters, including unpaired OBDLink CX devices. */
    @SuppressLint("MissingPermission")
    suspend fun scanNearbyBleDevices(scanDurationMs: Long = 5000L): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return@withContext emptyList()
        val found = java.util.concurrent.ConcurrentHashMap<String, String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.contains("OBD", true) || name.contains("ELM", true) || name.contains("vLinker", true)) {
                    found[result.device.address] = name
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }
        try {
            scanner.startScan(callback)
            delay(scanDurationMs)
        } catch (_: SecurityException) {
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) { }
        }
        found.entries.map { it.value to it.key }.sortedBy { it.first }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectAndProbe(macAddress: String): Result<AdapterCapabilities> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(macAddress)
                ?: return@withContext Result.failure(IllegalStateException("Bluetooth adapter unavailable"))

            bluetoothAdapter.cancelDiscovery()
            val useBle = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                (device.name ?: "").contains("OBDLink CX", ignoreCase = true)
            if (useBle) {
                val bleResult = bleTransport.connect(device)
                if (bleResult.isFailure) {
                    disconnect()
                    return@withContext Result.failure(bleResult.exceptionOrNull()!!)
                }
                activeTransport = "BLE"
            } else {
                val btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                btSocket.connect()
                activeTransport = "BLUETOOTH"
                socket = btSocket
                inputStream = btSocket.inputStream
                outputStream = btSocket.outputStream
            }
            probeConnectedAdapter(device.name ?: "Bluetooth OBD-II", macAddress)
        } catch (error: Exception) {
            disconnect()
            Result.failure(error)
        }
    }

    suspend fun connectWifi(host: String, port: Int = 35000): Result<AdapterCapabilities> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val connected = wifiTransport.connect(host, port)
            if (connected.isFailure) {
                disconnect()
                return@withContext Result.failure(connected.exceptionOrNull()!!)
            }
            activeTransport = "WIFI"
            probeConnectedAdapter("ELM327 Wi-Fi ($host:$port)", host)
        } catch (error: Exception) {
            disconnect()
            Result.failure(error)
        }
    }

    suspend fun connectUsb(deviceId: Int, baudRate: Int = 38400): Result<AdapterCapabilities> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val connected = usbTransport.connect(deviceId, baudRate)
            if (connected.isFailure) {
                disconnect()
                return@withContext Result.failure(connected.exceptionOrNull()!!)
            }
            activeTransport = "USB"
            probeConnectedAdapter("ELM327 USB #$deviceId", "USB:$deviceId")
        } catch (error: Exception) {
            disconnect()
            Result.failure(error)
        }
    }

    private suspend fun probeConnectedAdapter(
        deviceName: String,
        address: String
    ): Result<AdapterCapabilities> {
        return try {
            val atz = sendAtCommand("ATZ")
            sendAtCommand("ATE0")
            sendAtCommand("ATL0")
            val headersResp = sendAtCommand("ATH1")
            val sti = sendAtCommand("STI")
            val atal = sendAtCommand("ATAL")
            val stp61 = sendAtCommand("STP 61")
            val protocolResp = sendAtCommand("ATSP 6")

            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = deviceName,
                macAddress = address,
                atzResp = atz,
                stiResp = sti,
                atalResp = atal,
                stp61Resp = stp61,
                headersResp = headersResp,
                protocolResp = protocolResp
            )
            isDemoMode = false
            activeAdapterCapabilities = capabilities
            Result.success(capabilities)
        } catch (error: Exception) {
            disconnect()
            Result.failure(error)
        }
    }

    suspend fun activateSimulatedAdapter(preset: String): AdapterCapabilities = withContext(Dispatchers.Default) {
        disconnect()
        isDemoMode = true
        isSimulationActive = true
        delay(350)
        when (preset) {
            "MX_PLUS" -> CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "OBDLink MX+ (Simulated)",
                macAddress = "00:04:3E:8A:12:9F",
                atzResp = "ELM327 v1.4b",
                stiResp = "STN2255 v5.6.5",
                atalResp = "OK",
                stp61Resp = "OK"
            )
            "ELM_15" -> CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "Genuine ELM327 v1.5 (Simulated)",
                macAddress = "00:1D:A5:01:44:2B",
                atzResp = "ELM327 v1.5",
                stiResp = "?",
                atalResp = "OK",
                stp61Resp = "?"
            )
            else -> CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "Generic ELM327 v2.1 Clone (Simulated)",
                macAddress = "AA:BB:CC:11:22:33",
                atzResp = "ELM327 v2.1",
                stiResp = "?",
                atalResp = "?",
                stp61Resp = "?"
            )
        }
    }

    suspend fun sendAtCommand(command: String, timeoutMs: Long = 1200L): String = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            if (activeTransport == "USB") {
                usbTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(256); val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val n = usbTransport.read(bytes, 100)
                    if (n > 0) { val chunk = String(bytes, 0, n, Charsets.US_ASCII); if (chunk.contains('>')) { buffer.append(chunk.substringBefore('>')); break }; buffer.append(chunk) }
                    else delay(10)
                }
                buffer.toString().replace("\r", " ").replace("\n", " ").replace(command, "").trim()
            } else if (activeTransport == "BLE") {
                bleTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(512); val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    val n = bleTransport.read(bytes, 100)
                    if (n > 0) { val chunk = String(bytes, 0, n, Charsets.US_ASCII); if (chunk.contains('>')) { buffer.append(chunk.substringBefore('>')); break }; buffer.append(chunk) }
                    else delay(10)
                }
                buffer.toString().replace("\r", " ").replace("\n", " ").replace(command, "").trim()
            } else if (activeTransport == "WIFI") {
                wifiTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(256); val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    try { val n = wifiTransport.read(bytes); if (n > 0) { val chunk = String(bytes, 0, n, Charsets.US_ASCII); if (chunk.contains('>')) { buffer.append(chunk.substringBefore('>')); break }; buffer.append(chunk) } }
                    catch (_: java.net.SocketTimeoutException) { }
                    delay(10)
                }
                buffer.toString().replace("\r", " ").replace("\n", " ").replace(command, "").trim()
            } else {
                val out = outputStream; val inp = inputStream
                if (out == null || inp == null) return@withLock "NO_SOCKET"
                while (inp.available() > 0) inp.read()
                out.write(payload); out.flush()
                val buffer = StringBuilder(); val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < timeoutMs) {
                    if (inp.available() > 0) { val c = inp.read().toChar(); if (c == '>') break; buffer.append(c) } else delay(15)
                }
                buffer.toString().replace("\r", " ").replace("\n", " ").replace(command, "").trim()
            }
        }
    }

    /**
     * Connects to the Windows J2534 bridge. The selected PC driver performs ISO-TP;
     * the Android side exchanges complete UDS payloads only.
     */
    suspend fun connectJ2534Bridge(
        host: String,
        port: Int,
        pairingToken: String,
        requestCanId: Int,
        responseCanId: Int,
        can29Bit: Boolean,
        baudRate: Int = 500_000
    ): Result<String> = withContext(Dispatchers.IO) {
        disconnect()
        val bridge = J2534BridgeTransport(host, port, pairingToken)
        try {
            val info = bridge.connect(requestCanId, responseCanId, can29Bit, baudRate)
            j2534BridgeTransport = bridge
            activeTransport = "J2534_BRIDGE"
            isDemoMode = false
            isSimulationActive = false
            Result.success(info)
        } catch (error: Exception) {
            bridge.close()
            Result.failure(error)
        }
    }

    fun createJ2534BridgeUdsClient(
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 5000L
    ): UdsClient {
        val transport = j2534BridgeTransport
            ?: error("The Windows J2534 bridge is not connected.")
        check(activeTransport == "J2534_BRIDGE" && !isDemoMode && !isSimulationActive) {
            "J2534 UDS is disabled when no real PC bridge is connected."
        }
        return UdsClient(transport, policy, timeoutMs)
    }

    fun isJ2534BridgeConnected(): Boolean =
        activeTransport == "J2534_BRIDGE" && j2534BridgeTransport != null

    /**
     * Creates a UDS client through the adapter's documented STPX ISO-TP implementation.
     * Only a positively identified OBDLink MX+ is enabled; generic ELM clones are rejected.
     */
    fun createStnUdsClient(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int = 33,
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 3000L
    ): UdsClient {
        val capabilities = activeAdapterCapabilities
            ?: error("No real adapter has been probed.")
        check(!isDemoMode && !isSimulationActive) { "UDS CAN is disabled in demo mode." }
        check(capabilities.tier == AdapterTier.PRO_STN &&
            capabilities.supportsCanHeaders &&
            capabilities.deviceName.contains("OBDLink MX+", ignoreCase = true)) {
            "The STPX UDS provider is currently validated only for a connected OBDLink MX+ STN adapter with CAN headers enabled."
        }
        val transport = StnUdsPayloadTransport(
            requestCanId = requestCanId,
            responseCanId = responseCanId,
            protocolNumber = protocolNumber
        ) { command, commandTimeout -> sendAtCommand(command, commandTimeout) }
        return UdsClient(transport, policy, timeoutMs)
    }

    /** Creates the standards-based read-only OBD-II engine over the currently connected ELM transport. */
    fun createObd2FeatureEngine(): Obd2FeatureEngine = Obd2FeatureEngine(
        ObdCommandChannel { command, timeoutMs -> sendAtCommand(command, timeoutMs) }
    )

    fun disconnect() {
        // Close every transport independently: one failing close must not leak the others.
        runCatching { usbTransport.close() }
        runCatching { wifiTransport.close() }
        runCatching { bleTransport.close() }
        runCatching { j2534BridgeTransport?.close() }
        j2534BridgeTransport = null
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }

        inputStream = null
        outputStream = null
        socket = null
        activeTransport = "BLUETOOTH"
        isDemoMode = true
        isSimulationActive = false
        activeAdapterCapabilities = null
    }
}
