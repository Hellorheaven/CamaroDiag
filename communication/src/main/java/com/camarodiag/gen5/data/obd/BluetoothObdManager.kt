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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val commandSessionMutex = Mutex()
    // A superseded Wi-Fi handshake may finish after a new adapter selection.
    // Serialize teardown with epoch-based cleanup and state publication.
    private val adapterLifecycleLock = Any()

    private fun beginAdapterConnection(): Long = synchronized(adapterLifecycleLock) {
        disconnect()
        transportSessionEpoch.capture()
    }

    private fun disconnectIfCurrent(expectedEpoch: Long) {
        synchronized(adapterLifecycleLock) {
            if (transportSessionEpoch.isCurrent(expectedEpoch)) disconnect()
        }
    }

    private val supportedMode01PidsMutex = Mutex()
    @Volatile private var cachedSupportedMode01Pids: Set<Int>? = null
    @Volatile private var cachedSupportedMode01PidsAtMillis: Long = 0L
    private val usbTransport = ElmUsbTransport(context)
    private val connectorUsbContext = context.applicationContext
    private val connectorDiscoveryMutex = Mutex()
    private val wifiTransport = ElmWifiTransport()
    private val bleTransport = OBDLinkBleTransport(context)
    private var j2534BridgeTransport: J2534BridgeTransport? = null
    private var j2534BridgeRequestCanId: Int? = null
    private var j2534BridgeResponseCanId: Int? = null
    private var j2534BridgeProtocolNumber: Int? = null
    @Volatile private var activeTransport: String = "BLUETOOTH"
    /** Atomic, testable identity for transport work across disconnect/reconnect. */
    private val transportSessionEpoch = ObdTransportSessionEpoch()
    private var activeAdapterCapabilities: AdapterCapabilities? = null

    /** Capture the active transport before disconnect() resets its identity. */
    val currentTransportKind: String
        get() = activeTransport

    /** Actual read-only UDS availability for the currently connected STN hardware. */
    val isStnUdsReadAvailable: Boolean
        get() = StnUdsSessionAdmission.allowsRead(
            activeAdapterCapabilities, isObdCommandChannelAvailable
        )

    fun getUsbDevices(): List<String> = usbTransport.availableDevices()

    var isDemoMode: Boolean = true
        private set

    /** True only after the user explicitly selects a simulated adapter profile. */
    var isSimulationActive: Boolean = false
        private set

    /** True only when the active transport accepts ELM/SAE J1979 command traffic. */
    val isObdCommandChannelAvailable: Boolean
        get() = ObdTransportConnectionState.isCommandChannelAvailable(
            isDemoMode = isDemoMode,
            isSimulationActive = isSimulationActive,
            activeTransport = activeTransport,
            classicSocketConnected = socket?.isConnected == true,
            classicSocketClosed = socket?.isConnected != true,
            bleConnected = bleTransport.isConnected,
            usbConnected = usbTransport.isConnected,
            wifiConnected = wifiTransport.isConnected
        )

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
        val epoch = beginAdapterConnection()
        var ownClassicSocket: BluetoothSocket? = null
        try {
            val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(macAddress)
                ?: return@withContext Result.failure(IllegalStateException("Bluetooth adapter unavailable"))

            bluetoothAdapter.cancelDiscovery()
            val useBle = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                (device.name ?: "").contains("OBDLink CX", ignoreCase = true)
            if (useBle) {
                val bleResult = bleTransport.connect(device)
                if (bleResult.isFailure) {
                    disconnectIfCurrent(epoch)
                    return@withContext Result.failure(bleResult.exceptionOrNull()!!)
                }
                synchronized(adapterLifecycleLock) {
                    transportSessionEpoch.requireQueuedCurrent(epoch)
                    activeTransport = "BLE"
                }
            } else {
                val btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                ownClassicSocket = btSocket
                // A pending RFCOMM connect must be owned by its exact epoch;
                // disconnect() can then close the pending handshake on a switch.
                synchronized(adapterLifecycleLock) {
                    transportSessionEpoch.requireQueuedCurrent(epoch)
                    socket = btSocket
                }
                btSocket.connect()
                val connectedInput = btSocket.inputStream
                val connectedOutput = btSocket.outputStream
                synchronized(adapterLifecycleLock) {
                    transportSessionEpoch.requireQueuedCurrent(epoch)
                    activeTransport = "BLUETOOTH"
                    inputStream = connectedInput
                    outputStream = connectedOutput
                }
            }
            probeConnectedAdapter(device.name ?: "Bluetooth OBD-II", macAddress, epoch)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            runCatching { ownClassicSocket?.close() }
            disconnectIfCurrent(epoch)
            throw cancelled
        } catch (error: Exception) {
            runCatching { ownClassicSocket?.close() }
            disconnectIfCurrent(epoch)
            Result.failure(error)
        }
    }

    suspend fun connectWifi(host: String, port: Int = 35000): Result<AdapterCapabilities> = withContext(Dispatchers.IO) {
        // Invalid manual input must not tear down an otherwise working adapter.
        try {
            ElmWifiConnectionPolicy.validate(host, port, 4000)
        } catch (invalid: IllegalArgumentException) {
            return@withContext Result.failure(invalid)
        }
        val epoch = beginAdapterConnection()
        try {
            val connected = wifiTransport.connect(host, port)
            if (connected.isFailure) {
                disconnectIfCurrent(epoch)
                return@withContext Result.failure(connected.exceptionOrNull()!!)
            }
            synchronized(adapterLifecycleLock) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                activeTransport = "WIFI"
            }
            probeConnectedAdapter("ELM327 Wi-Fi ($host:$port)", host, epoch)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // An older connection's cancellation cannot tear down a successor.
            disconnectIfCurrent(epoch)
            throw cancelled
        } catch (error: Exception) {
            disconnectIfCurrent(epoch)
            Result.failure(error)
        }
    }

    /** Discovery only. Close the probe before returning: never expose an ELM channel. */
    suspend fun discoverCamaroDiagConnector(deviceId: Int, binary: Boolean = false): Result<AdapterCapabilities> =
        isolatedConnectorUsb(deviceId, binary) { channel, ensureCurrent ->
            val report = if (binary) {
                CamaroDiagConnectorBinaryDiscovery.inspect(channel, ensureCurrent)
            } else {
                CamaroDiagConnectorDiscovery.inspect(channel, ensureCurrent)
            }
            report.toAdapterCapabilities()
        }

    /** Offline CDJ inspection remains; physical USB checkpoint capture is deferred to C5. */
    suspend fun captureConnectorCheckpoint(deviceId: Int, isolatedConfirmed: Boolean): Result<ByteArray?> {
        if (!isolatedConfirmed) return Result.failure(IllegalArgumentException("Isolated board confirmation required"))
        // Do not reference the removed experimental CDJ/USB physical reader or
        // mistake an offline checkpoint for a verified board acquisition.
        return Result.failure(UnsupportedOperationException(
            "Physical CDJ/USB checkpoint capture on device $deviceId is deferred to C5."
        ))
    }

    private suspend fun <T> isolatedConnectorUsb(
        deviceId: Int,
        requireCdc: Boolean,
        inspect: (CamaroDiagConnectorDiscovery.Channel, () -> Unit) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
            connectorDiscoveryMutex.withLock {
                // This manager boundary is entered only after the ViewModel
                // selected a new adapter and cleared the previous UI session.
                // Keep physical teardown in step with that state transition.
                disconnect()
                val epoch = transportSessionEpoch.capture()
                val probe = ElmUsbTransport(connectorUsbContext)
                try {
                    val enumerated = probe.availableDevices()
                    if (!CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(deviceId, enumerated)) {
                        return@withLock Result.failure(IllegalStateException(
                            "USB connector discovery requires a named non-KKL CamaroDiagConnectors device"
                        ))
                    }
                    // A dedicated probe never shares the old ELM channel.
                    probe.connect(
                        deviceId, CamaroDiagConnectorDiscovery.BAUD_RATE,
                        discoveryOnly = true, requireCdc = requireCdc
                    ).getOrThrow()
                    val context = currentCoroutineContext()
                    val ensureCurrent = {
                        context.ensureActive()
                        transportSessionEpoch.requireQueuedCurrent(epoch)
                    }
                    val channel = object : CamaroDiagConnectorDiscovery.Channel {
                        override fun write(bytes: ByteArray) {
                            ensureCurrent()
                            probe.write(bytes)
                        }
                        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                            ensureCurrent()
                            return probe.read(buffer, timeoutMs)
                        }
                    }
                    val result = inspect(channel, ensureCurrent)
                    ensureCurrent()
                    Result.success(result)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                } finally {
                    probe.close()
                }
            }
        }

    suspend fun connectUsb(deviceId: Int, baudRate: Int = 38400): Result<AdapterCapabilities> = withContext(Dispatchers.IO) {
        try {
            UsbAdapterConnectParameters.requireValid(deviceId, baudRate)
        } catch (invalid: IllegalArgumentException) {
            return@withContext Result.failure(invalid)
        }
        val epoch = beginAdapterConnection()
        try {
            // Re-check the currently enumerated USB device at the manager boundary.
            // Neither a legacy UI entry nor a saved ELM profile may bypass discovery.
            val labels = usbTransport.availableDevices()
            if (CamaroDiagConnectorDiscovery.requiresLegacyKklIsolation(deviceId, labels)) {
                return@withContext Result.failure(IllegalStateException("KKL requires its own qualified transport"))
            }
            if (CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(deviceId, labels)) {
                return@withContext Result.failure(IllegalStateException("CamaroDiagConnectors requires dedicated USB discovery"))
            }
            val connected = usbTransport.connect(deviceId, baudRate)
            if (connected.isFailure) {
                disconnectIfCurrent(epoch)
                return@withContext Result.failure(connected.exceptionOrNull()!!)
            }
            synchronized(adapterLifecycleLock) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                activeTransport = "USB"
            }
            probeConnectedAdapter("ELM327 USB #$deviceId", "USB:$deviceId", epoch)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            disconnectIfCurrent(epoch)
            throw cancelled
        } catch (error: Exception) {
            disconnectIfCurrent(epoch)
            Result.failure(error)
        }
    }

    private suspend fun probeConnectedAdapter(
        deviceName: String,
        address: String,
        expectedEpoch: Long = transportSessionEpoch.capture()
    ): Result<AdapterCapabilities> {
        // Every probe request is tied to the original adapter generation,
        // including the last 0100. A newer selection must never receive the
        // remaining AT commands of the cancelled probe.
        suspend fun probeCommand(command: String, timeoutMs: Long = 1200L): String =
            commandSessionMutex.withLock {
                transportSessionEpoch.requireQueuedCurrent(expectedEpoch)
                sendAtCommandInternal(command, timeoutMs, expectedEpoch = expectedEpoch)
            }
        return try {
            val atz = probeCommand("ATZ")
            probeCommand("ATE0")
            probeCommand("ATL0")
            val headersResp = probeCommand("ATH1")
            val sti = probeCommand("STI")
            val atal = probeCommand("ATAL")
            val stp61 = probeCommand("STP 61")
            val protocolResp = probeCommand("ATSP 6")
            // A real 0100 response proves that the connected physical command path
            // transmitted a request and received an ECU reply; AT header support alone does not.
            val transmitProbeResp = probeCommand("0100", 2_500L)

            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = deviceName,
                macAddress = address,
                atzResp = atz,
                stiResp = sti,
                atalResp = atal,
                stp61Resp = stp61,
                headersResp = headersResp,
                protocolResp = protocolResp,
                transmitProbeResp = transmitProbeResp
            )
            synchronized(adapterLifecycleLock) {
                transportSessionEpoch.requireQueuedCurrent(expectedEpoch)
                isDemoMode = false
                activeAdapterCapabilities = capabilities
            }
            Result.success(capabilities)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            disconnectIfCurrent(expectedEpoch)
            throw cancelled
        } catch (error: Exception) {
            disconnectIfCurrent(expectedEpoch)
            Result.failure(error)
        }
    }

    suspend fun activateSimulatedAdapter(preset: String): AdapterCapabilities = withContext(Dispatchers.Default) {
        val epoch = synchronized(adapterLifecycleLock) {
            disconnect()
            isDemoMode = true
            isSimulationActive = true
            transportSessionEpoch.capture()
        }
        delay(350)
        // A pending demo animation cannot report its simulated adapter as
        // current after USB/BLE/Wi-Fi/J2534 was selected instead.
        transportSessionEpoch.requireQueuedCurrent(epoch)
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

    /**
     * Runs a multi-command OBD transaction without allowing live telemetry or
     * another diagnostic action to interleave adapter setup and response parsing.
     */
    suspend fun <T> withObdCommandSession(block: suspend (Obd2FeatureEngine) -> T): T {
        val requestedEpoch = transportSessionEpoch.capture()
        check(isObdCommandChannelAvailable) {
            "No ELM/OBD-II command channel is available on the active transport."
        }
        commandSessionMutex.lock()
        try {
            transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
            check(isObdCommandChannelAvailable) {
                "OBD command channel disconnected while waiting for the previous operation."
            }
            val engine = Obd2FeatureEngine(
                ObdCommandChannel { command, timeoutMs ->
                    sendAtCommandInternal(command, timeoutMs, expectedEpoch = requestedEpoch)
                }
            )
            return block(engine)
        } finally {
            commandSessionMutex.unlock()
        }
    }

    /**
     * Reads a Mode 01 PID only after discovering that the connected ECU advertises it.
     * The support bitmap is cached for the lifetime of the current adapter connection.
     */
    suspend fun readMode01PidIfSupported(pid: Int): String? {
        if (isJ2534StandardObdAvailable()) return readJ2534PidIfSupported(pid)
        check(isObdCommandChannelAvailable) {
            "No ELM/OBD-II command channel is available on the active transport."
        }
        val supported = supportedMode01PidsMutex.withLock {
            val cached = cachedSupportedMode01Pids
            val cacheAgeMs = System.currentTimeMillis() - cachedSupportedMode01PidsAtMillis
            if (cached != null && (cached.isNotEmpty() || cacheAgeMs < 10_000L)) {
                cached
            } else {
                withObdCommandSession { engine ->
                    engine.discoverSupportedPids()
                }.also { discovered ->
                    // Keep an empty result briefly to avoid repeating a slow bitmap timeout
                    // for every live PID; retry after 10 seconds if the ECU/clone was not ready.
                    cachedSupportedMode01Pids = discovered
                    cachedSupportedMode01PidsAtMillis = System.currentTimeMillis()
                }
            }
        }
        return withObdCommandSession { engine ->
            engine.readMode01PidIfSupported(pid, supported)
        }
    }

    /**
     * One physical, read-only Mode 01 survey of the fuel-composition and trim
     * PIDs on the same connection epoch. An unsupported PID is NOT read.
     * No ELM fallback while a J2534 bridge is selected, and no simulator.
     */
    suspend fun readPhysicalFuelSurvey(): Lfx2012FuelReadOnly.Survey {
        check(!isDemoMode && !isSimulationActive) {
            "FlexFuel observations require a physical ECU connection, never demo data."
        }
        val epoch = transportSessionEpoch.capture()
        if (activeTransport == "J2534_BRIDGE") {
            check(isJ2534BridgeConnected()) {
                "The selected J2534 physical fuel channel disconnected; no ELM fallback."
            }
            return commandSessionMutex.withLock {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                check(isJ2534StandardObdAvailable()) {
                    "The connected J2534 channel cannot read standard OBD fuel PIDs."
                }
                val bridge = checkNotNull(j2534BridgeTransport)
                val supported = bridge.discoverStandardObdPids()
                transportSessionEpoch.requireQueuedCurrent(epoch)
                val replies = linkedMapOf<Int, String?>()
                for (pid in Lfx2012FuelReadOnly.PIDS) {
                    transportSessionEpoch.requireQueuedCurrent(epoch)
                    replies[pid] = if (pid in supported)
                        bridge.readStandardObdPid(pid) else null
                    transportSessionEpoch.requireQueuedCurrent(epoch)
                }
                Lfx2012FuelReadOnly.Survey(
                    Lfx2012FuelReadOnly.Source.J2534, supported, replies
                )
            }
        }
        check(isObdCommandChannelAvailable) {
            "No physical ELM/STN OBD channel is connected for the fuel survey."
        }
        return withObdCommandSession { engine ->
            transportSessionEpoch.requireQueuedCurrent(epoch)
            val supported = engine.discoverSupportedPids()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            val replies = linkedMapOf<Int, String?>()
            for (pid in Lfx2012FuelReadOnly.PIDS) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                replies[pid] = engine.readMode01PidIfSupported(pid, supported)
                transportSessionEpoch.requireQueuedCurrent(epoch)
            }
            Lfx2012FuelReadOnly.Survey(
                Lfx2012FuelReadOnly.Source.ELM, supported, replies
            )
        }
    }

    /**
     * Console operator read, using the active real transport without silently
     * falling back from J2534 to an ELM adapter or to simulation.
     */
    suspend fun sendReadOnlyConsoleCommand(request: SafeObdRequest): String {
        require(SafeObdRequestPolicy.parse(request.elmCommand) == request) {
            "Invalid operator OBD read command."
        }
        if (!isJ2534BridgeConnected()) {
            check(isObdCommandChannelAvailable) { "No connected OBD read channel." }
            val epoch = transportSessionEpoch.capture()
            return commandSessionMutex.withLock {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                check(isObdCommandChannelAvailable) { "OBD channel disconnected." }
                sendAtCommandInternal(request.elmCommand, 4_000L, expectedEpoch = epoch)
            }
        }
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable()) { "Physical J2534 OBD reading unavailable." }
            val result = checkNotNull(j2534BridgeTransport).readOnlyConsoleRequest(request)
            transportSessionEpoch.requireQueuedCurrent(epoch)
            result
        }
    }

    /** The standard OBD capability is not an ELM/AT command channel. */
    fun isJ2534StandardObdAvailable(): Boolean =
        isJ2534BridgeConnected() && j2534BridgeTransport?.isStandardObdPhysicalReadEnabled == true

    fun isStandardObdReadAvailable(): Boolean =
        isObdCommandChannelAvailable || isJ2534StandardObdAvailable()

    private suspend fun readJ2534PidIfSupported(pid: Int): String? {
        require(pid in 1..255)
        val epoch = transportSessionEpoch.capture()
        return supportedMode01PidsMutex.withLock {
            commandSessionMutex.withLock session@{
                transportSessionEpoch.requireQueuedCurrent(epoch)
                check(isJ2534StandardObdAvailable())
                val bridge = checkNotNull(j2534BridgeTransport)
                val age = System.currentTimeMillis() - cachedSupportedMode01PidsAtMillis
                val cached = cachedSupportedMode01Pids
                val supported = if (cached != null && (cached.isNotEmpty() || age < 10_000L)) {
                    cached
                } else {
                    bridge.discoverStandardObdPids().also {
                        transportSessionEpoch.requireQueuedCurrent(epoch)
                        cachedSupportedMode01Pids = it
                        cachedSupportedMode01PidsAtMillis = System.currentTimeMillis()
                    }
                }
                if (pid !in supported) return@session null
                val result = bridge.readStandardObdPid(pid)
                transportSessionEpoch.requireQueuedCurrent(epoch)
                result
            }
        }
    }

    /** Read both available odometer PIDs from the same physical connection. */
    suspend fun readJ2534OdometerPids(): Pair<String?, String?> {
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable())
            val bridge = checkNotNull(j2534BridgeTransport)
            val supported = bridge.discoverStandardObdPids()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            suspend fun read(pid: Int): String? {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                val value = if (pid in supported) bridge.readStandardObdPid(pid) else null
                transportSessionEpoch.requireQueuedCurrent(epoch)
                return value
            }
            read(0xA6) to read(0xD3)
        }
    }

    suspend fun readJ2534StandardDtcs(): PhysicalObdDtcSnapshot {
        val epoch = transportSessionEpoch.capture()
        check(isJ2534StandardObdAvailable())
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable())
            val result = checkNotNull(j2534BridgeTransport).readStandardObdDtcs()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            result
        }
    }

    /** Independent, fresh physical capability probe for the owner test suite. */
    suspend fun probeJ2534StandardPids(): Set<Int> {
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable())
            val pids = checkNotNull(j2534BridgeTransport).discoverStandardObdPids()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            pids
        }
    }

    suspend fun probeJ2534StandardReadiness(): ByteArray {
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable())
            val response = checkNotNull(j2534BridgeTransport).exchange(byteArrayOf(1,1), 1800)
            val data = J2534StandardObd.pidData(1,response)
            require(data.size == 4) { "Invalid physical readiness payload." }
            transportSessionEpoch.requireQueuedCurrent(epoch)
            data
        }
    }

    suspend fun fullJ2534StandardObdScan(): ObdScanReport {
        val epoch = transportSessionEpoch.capture()
        check(isJ2534StandardObdAvailable())
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdAvailable())
            val report = checkNotNull(j2534BridgeTransport).fullStandardObdScan()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            report
        }
    }

    fun isJ2534StandardObdClearAvailable(): Boolean =
        isJ2534StandardObdAvailable() && j2534BridgeTransport?.isStandardObdClearEnabled == true

    suspend fun clearJ2534StandardDtcs(
        approval: EcuWriteConsentFlow.Approval,
        beforeTransmit: () -> Unit
    ) {
        require(approval.warningAcknowledged && approval.confirmedYes) {
            "Risk notice OK and separate YES are required before clearing ECU DTCs."
        }
        val epoch = transportSessionEpoch.capture()
        check(isJ2534StandardObdClearAvailable())
        commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534StandardObdClearAvailable())
            checkNotNull(j2534BridgeTransport).clearStandardObdDtcs(approval) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                beforeTransmit()
            }
            transportSessionEpoch.requireQueuedCurrent(epoch)
        }
    }

    /** Invalidates ECU-specific support data after a vehicle identity/profile change. */
    fun invalidateObdPidSupportCache() {
        cachedSupportedMode01Pids = null
        cachedSupportedMode01PidsAtMillis = 0L
    }

    /**
     * Captures passive CAN traffic from a real, header-capable ELM/STN adapter.
     * The command session stays exclusively locked for the whole capture so live
     * PID polling or another diagnostic request cannot corrupt the monitor stream.
     */
    suspend fun captureCanMonitor(
        durationMs: Long = Long.MAX_VALUE,
        can29Bit: Boolean = false,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val requestedEpoch = transportSessionEpoch.capture()
        check(isObdCommandChannelAvailable && !isDemoMode && !isSimulationActive) {
            "Live CAN monitoring requires a connected physical OBD command adapter."
        }
        val capabilities = activeAdapterCapabilities
            ?: error("The connected adapter has not been identified.")
        check(capabilities.supportsCanHeaders) {
            "The connected adapter does not report CAN header support."
        }
        require(durationMs >= 1_000L) {
            "CAN monitor duration must be at least one second."
        }

        commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
            check(isObdCommandChannelAvailable) { "CAN monitor connection changed while waiting." }
            suspend fun boundCommand(command: String, timeoutMs: Long, onChunk: ((String) -> Unit)? = null): String =
                sendAtCommandInternal(command, timeoutMs, expectedEpoch = requestedEpoch, onChunk = onChunk)
            val isStnAdapter = capabilities.tier == AdapterTier.PRO_STN
            // STMA emits ISO-15765 OBD messages on STN devices; STM is required for raw CAN frames.
            // STM uses the current filter table, so clear it for this session and restore
            // automatic OBD filtering after the capture.
            val monitorCommand = if (isStnAdapter) "STM" else "ATMA"
            val captured = CanCaptureTextBuffer()
            val monitorErrors = CanMonitorErrorTracker()
            try {
                // Configure one physical CAN format and clear STN receive filters.
                // All steps require explicit adapter acknowledgement before streaming.
                for (command in CanMonitorProtocolPolicy.setupCommands(can29Bit, isStnAdapter)) {
                    val timeout = if (command.startsWith("ATSP")) 1_500L else 1_000L
                    val answer = boundCommand(command, timeout)
                    if (!CanConsoleAdapterRestorationPolicy.accepted(answer))
                        throw CanAdapterRestorationException()
                }
                val monitorResponse = boundCommand(monitorCommand, durationMs) { chunk ->
                    captured.append(chunk)
                    monitorErrors.append(chunk)
                    onChunk(chunk)
                }
                check(CanMonitorCompletionPolicy.accepted(monitorResponse) &&
                    !monitorErrors.hasFailureAtEnd()) {
                    "The connected adapter failed or rejected the CAN monitor command."
                }
            } finally {
                // ELM/STN monitor mode has no normal prompt; ESC returns the adapter
                // to command mode even when the caller cancels the capture.
                withContext(NonCancellable) {
                    // ESC may return only a prompt. A failed ESC still requires a
                    // fail-closed session even if later configuration commands respond.
                    var restored = runCatching { boundCommand("\u001B", 1_000L) }.isSuccess
                    val commands = CanMonitorProtocolPolicy.restoreCommands(isStnAdapter)
                    // Restore the command-channel formatting established at probe time.
                    for (command in commands) {
                        val answer = runCatching { boundCommand(command, 1_000L) }.getOrNull()
                        if (answer == null || !CanConsoleAdapterRestorationPolicy.accepted(answer))
                            restored = false
                    }
                    if (!restored) {
                        // An old operation cannot tear down a replacement adapter.
                        if (!transportSessionEpoch.isCurrent(requestedEpoch))
                            throw ObdSupersededSessionException(CanAdapterRestorationException())
                        val transportKind = activeTransport
                        disconnect()
                        throw CanAdapterRestorationException(transportKind)
                    }
                }
            }
            captured.snapshot()
        }
    }

    /**
     * Transmits one explicitly addressed Classical CAN frame through a physical ELM/STN
     * adapter. Receive/header support is checked separately from simulation state; this
     * path never routes through the demo adapter or the ISO-TP-only J2534 bridge.
     */
    suspend fun sendRawCanFrameWithStatus(
        frame: RawCanFrame,
        approval: EcuWriteConsentFlow.Approval,
        // Passive read-only observation, never an alternative TX permission.
        // The console stores all physical RX chunks before the matcher drops
        // older events from its deliberately bounded recent-record window.
        onPhysicalResponseChunk: (String, Long) -> Unit = { _, _ -> },
        isOperatorContextCurrent: () -> Boolean
    ): CanConsoleRawTransmissionResult =
        withContext(Dispatchers.IO) {
        val requestedEpoch = transportSessionEpoch.capture()
        // The raw payload may be a mutating ECU command. No public no-approval
        // convenience entrypoint may bypass the two independent UI actions.
        EcuWriteConsentFlow.requireCurrentForTransmit(approval, isOperatorContextCurrent)
        check(isObdCommandChannelAvailable && !isDemoMode && !isSimulationActive) {
            "Raw CAN transmission requires a connected physical adapter."
        }
        val capabilities = activeAdapterCapabilities
            ?: error("The connected adapter has not been identified.")
        check(capabilities.supportsCanHeaders) {
            "The connected adapter has not confirmed CAN header support."
        }
        check(capabilities.supportsCanTransmit) {
            "CAN transmission is disabled: no real ECU response to the read-only 0100 capability probe was observed."
        }
        check(activeTransport in setOf("BLUETOOTH", "BLE", "USB", "WIFI")) {
            "Raw CAN transmission is not available on this transport."
        }
        require(frame.data.size in 1..8) { "Classical CAN payloads must contain 1 to 8 bytes." }
        require(if (frame.isExtended) frame.canId in 0..0x1FFFFFFFL else frame.canId in 0..0x7FFL) {
            "CAN identifier is outside the selected 11-bit/29-bit range."
        }
        require(SafeRawCanTransmitPolicy.allows(frame)) {
            "The raw CAN console refused an unsafe or unqualified diagnostic request."
        }

        commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
            check(isObdCommandChannelAvailable) { "CAN transmission connection changed while waiting." }
            suspend fun boundCommand(
                command: String,
                timeoutMs: Long,
                onChunk: ((String) -> Unit)? = null,
                beforeTransmit: () -> Unit = {}
            ): String = sendAtCommandInternal(
                command, timeoutMs, expectedEpoch = requestedEpoch,
                onChunk = onChunk, beforeTransmit = beforeTransmit
            )
            val protocol = if (frame.isExtended) "7" else "6"
            val header = if (frame.isExtended) {
                frame.canId.toString(16).uppercase().padStart(8, '0')
            } else {
                frame.canId.toString(16).uppercase().padStart(3, '0')
            }
            val timedCorrelation = CanConsoleLiveDiagnosticCorrelation(
                frame, requireExplicitStart = true
            )
            try {
                val protocolResponse = boundCommand("ATSP$protocol", 1_500L)
                check(CanConsoleAdapterRestorationPolicy.accepted(protocolResponse)) {
                    "The adapter rejected the selected CAN protocol."
                }
                val formattingResponse = boundCommand("ATCAF0", 1_500L)
                check(CanConsoleAdapterRestorationPolicy.accepted(formattingResponse)) {
                    "The adapter cannot enter raw CAN formatting mode."
                }
                // CAF0 controls formatting, not automatic ISO-TP Flow Control.
                // Reset any previous user-defined FC header/bytes to mode 0,
                // then require CFC1 on the actual physical adapter.
                for (command in CanConsoleIsotpFlowControlPolicy.setupCommands()) {
                    val flowControlResponse = boundCommand(command, 1_500L)
                    check(CanConsoleIsotpFlowControlPolicy.adapterConfirmed(flowControlResponse)) {
                        "The adapter did not confirm ISO-TP automatic flow control."
                    }
                }
                val headerResponse = boundCommand("ATSH $header", 1_500L)
                check(CanConsoleAdapterRestorationPolicy.accepted(headerResponse)) {
                    "The adapter rejected CAN header $header."
                }
                val headersResponse = boundCommand("ATH1", 1_000L)
                val spacesResponse = boundCommand("ATS1", 1_000L)
                check(CanConsoleAdapterRestorationPolicy.accepted(headersResponse) &&
                    CanConsoleAdapterRestorationPolicy.accepted(spacesResponse)) {
                    "The adapter cannot expose received CAN identifiers and byte boundaries."
                }
                val payload = frame.data.joinToString(" ") {
                    "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF)
                }
                timedCorrelation.beginRequest(System.nanoTime() / 1_000_000L)
                // AT configuration may be queued, but the ECU payload itself
                // must still belong to the originally confirmed account,
                // vehicle and adapter at the exact physical TX boundary.
                val frameResponse = boundCommand(
                    payload, 4_000L,
                    onChunk = { chunk ->
                        val receivedAtWallClock = System.currentTimeMillis()
                        timedCorrelation.append(
                            chunk, System.nanoTime() / 1_000_000L, receivedAtWallClock
                        )
                        // This callback gets the actual adapter RX chunks,
                        // including records beyond the 300-item diagnostic window.
                        onPhysicalResponseChunk(chunk, receivedAtWallClock)
                    },
                    beforeTransmit = {
                        transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
                        EcuWriteConsentFlow.requireCurrentForTransmit(
                            approval, isOperatorContextCurrent
                        )
                    }
                )
                val diagnosticStatus = timedCorrelation.complete(
                    System.nanoTime() / 1_000_000L, System.currentTimeMillis()
                )
                // An explicit adapter OK establishes TX only. A correlated ECU
                // reply may also establish completion, but unmatched bus traffic
                // and late ISO-TP continuations never do.
                check(CanConsolePhysicalTransmissionPolicy.confirmed(
                    adapterOutput = frameResponse,
                    diagnosticStatus = diagnosticStatus,
                    hasExplicitAck = timedCorrelation.hasExplicitAdapterAck(),
                    hasAdapterError = timedCorrelation.hasAdapterError()
                )) {
                    "The adapter did not confirm the raw CAN transmission."
                }
                CanConsoleRawTransmissionResult(
                    frameResponse, diagnosticStatus, timedCorrelation.recentReceivedRecords()
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A complete CAN line may be missing only its CR/LF when the
                // stream was cancelled. Drain it for the history, not for TX success.
                timedCorrelation.complete(
                    System.nanoTime() / 1_000_000L, System.currentTimeMillis()
                )
                throw CanConsolePhysicalExchangeCancelled(
                    timedCorrelation.recentReceivedRecords(), cancelled
                )
            } catch (superseded: ObdSupersededSessionException) {
                throw superseded
            } catch (error: Exception) {
                // An adapter error/timeout does not erase already observed RX.
                timedCorrelation.complete(
                    System.nanoTime() / 1_000_000L, System.currentTimeMillis()
                )
                throw CanConsolePhysicalExchangeException(
                    timedCorrelation.recentReceivedRecords(), error
                )
            } finally {
                // Return to the app's configured 11-bit HS-CAN / automatic-format state.
                withContext(NonCancellable) {
                    // A successful TX is not permission to keep using an adapter whose
                    // CAN protocol/header could not be restored. Try every cleanup step,
                    // then close the physical session if *any* step was unconfirmed.
                    var restored = true
                    for ((command, timeout) in listOf(
                        "ATSP6" to 1_500L,
                        "ATSH 7DF" to 1_500L,
                        "ATCAF1" to 1_500L,
                        CanConsoleIsotpFlowControlPolicy.RESTORE_AUTOMATIC_MODE to 1_500L,
                        CanConsoleIsotpFlowControlPolicy.ENABLE_AUTOMATIC to 1_500L,
                        "ATH1" to 1_000L,
                        "ATS0" to 1_000L
                    )) {
                        val answer = runCatching { boundCommand(command, timeout) }.getOrNull()
                        if (answer == null || !CanConsoleAdapterRestorationPolicy.accepted(answer))
                            restored = false
                    }
                    if (!restored) {
                        // An old operation cannot tear down a replacement adapter.
                        if (!transportSessionEpoch.isCurrent(requestedEpoch))
                            throw ObdSupersededSessionException(CanAdapterRestorationException())
                        val transportKind = activeTransport
                        disconnect()
                        throw CanConsolePhysicalExchangeException(
                            timedCorrelation.recentReceivedRecords(),
                            CanAdapterRestorationException(transportKind)
                        )
                    }
                }
            }
        }
    }

    suspend fun sendAtCommand(
        command: String,
        timeoutMs: Long = 1200L,
        beforeTransmit: () -> Unit = {}
    ): String {
        val requestedEpoch = transportSessionEpoch.capture()
        return withContext(Dispatchers.IO) {
            commandSessionMutex.withLock {
                transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
                // Adapter probing calls this before capability detection has cleared
                // demo mode. The epoch check is valid here; a channel-available
                // check would incorrectly reject every initial ATZ/0100 probe.
                sendAtCommandInternal(
                    command, timeoutMs, expectedEpoch = requestedEpoch,
                    beforeTransmit = beforeTransmit
                )
            }
        }
    }

    private suspend fun sendAtCommandInternal(
        command: String,
        timeoutMs: Long,
        expectedEpoch: Long? = null,
        onChunk: ((String) -> Unit)? = null,
        beforeTransmit: () -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            // The operation may have waited on the command mutex across a reconnect.
            if (expectedEpoch != null) transportSessionEpoch.requireQueuedCurrent(expectedEpoch)
            val sessionEpoch = expectedEpoch ?: transportSessionEpoch.capture()
            val sessionTransport = activeTransport
            val commandContext = currentCoroutineContext()
            fun ensureSameSession() {
                commandContext.ensureActive()
                transportSessionEpoch.requireCurrent(sessionEpoch)
                if (activeTransport != sessionTransport) {
                    throw ObdTransportDisconnectedException("OBD connection changed during command")
                }
            }
            val payload = (command.trim() + "\r").toByteArray(Charsets.US_ASCII)
            ensureSameSession()
            // Check the operator's account, vehicle and connection consent
            // at the physical command boundary, not only at queue admission.
            // Read-only commands use the default no-op callback.
            beforeTransmit()
            ensureSameSession()
            try {
            if (sessionTransport == "USB") {
                usbTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(256); val start = System.currentTimeMillis()
                var promptReceived = false
                while (System.currentTimeMillis() - start < timeoutMs) {
                    ensureSameSession()
                    val n = usbTransport.read(bytes, 100)
                    ensureSameSession()
                    if (n > 0) {
                        val chunk = String(bytes, 0, n, Charsets.US_ASCII)
                        val payloadChunk = chunk.substringBefore('>')
                        if (buffer.length < 1024 || onChunk == null) {
                            buffer.append(if (onChunk == null) payloadChunk else payloadChunk.take(1024 - buffer.length))
                        }
                        if (payloadChunk.isNotEmpty()) onChunk?.invoke(payloadChunk)
                        if (chunk.contains('>')) { promptReceived = true; break }
                    } else if (n < 0) {
                        throw java.io.EOFException("USB OBD adapter disconnected while reading")
                    } else delay(10)
                }
                ensureSameSession()
                ElmResponseCompletion.requirePrompt(
                    command,
                    streaming = ElmResponseCompletion.isUnterminatedMonitorCommand(command, onChunk != null),
                    promptReceived = promptReceived
                )
                ensureSameSession()
                normalizeElmReply(buffer.toString(), command)
            } else if (sessionTransport == "BLE") {
                bleTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(512); val start = System.currentTimeMillis()
                var promptReceived = false
                while (System.currentTimeMillis() - start < timeoutMs) {
                    ensureSameSession()
                    val n = bleTransport.read(bytes, 100)
                    ensureSameSession()
                    if (n > 0) {
                        val chunk = String(bytes, 0, n, Charsets.US_ASCII)
                        val payloadChunk = chunk.substringBefore('>')
                        if (buffer.length < 1024 || onChunk == null) {
                            buffer.append(if (onChunk == null) payloadChunk else payloadChunk.take(1024 - buffer.length))
                        }
                        if (payloadChunk.isNotEmpty()) onChunk?.invoke(payloadChunk)
                        if (chunk.contains('>')) { promptReceived = true; break }
                    } else delay(10)
                }
                ensureSameSession()
                ElmResponseCompletion.requirePrompt(
                    command,
                    streaming = ElmResponseCompletion.isUnterminatedMonitorCommand(command, onChunk != null),
                    promptReceived = promptReceived
                )
                ensureSameSession()
                normalizeElmReply(buffer.toString(), command)
            } else if (sessionTransport == "WIFI") {
                wifiTransport.write(payload)
                val buffer = StringBuilder(); val bytes = ByteArray(256); val start = System.currentTimeMillis()
                var promptReceived = false
                while (System.currentTimeMillis() - start < timeoutMs) {
                    ensureSameSession()
                    try {
                        val n = wifiTransport.read(bytes)
                        ensureSameSession()
                        if (n > 0) {
                            val chunk = String(bytes, 0, n, Charsets.US_ASCII)
                            val payloadChunk = chunk.substringBefore('>')
                            if (buffer.length < 1024 || onChunk == null) {
                            buffer.append(if (onChunk == null) payloadChunk else payloadChunk.take(1024 - buffer.length))
                        }
                            if (payloadChunk.isNotEmpty()) onChunk?.invoke(payloadChunk)
                            if (chunk.contains('>')) { promptReceived = true; break }
                        } else if (n < 0) {
                            throw java.io.EOFException("Wi-Fi OBD adapter closed the connection")
                        }
                    } catch (_: java.net.SocketTimeoutException) { }
                    delay(10)
                }
                ensureSameSession()
                ElmResponseCompletion.requirePrompt(
                    command,
                    streaming = ElmResponseCompletion.isUnterminatedMonitorCommand(command, onChunk != null),
                    promptReceived = promptReceived
                )
                ensureSameSession()
                normalizeElmReply(buffer.toString(), command)
            } else {
                check(sessionTransport == "BLUETOOTH") {
                    "An ELM command cannot be sent through the selected transport."
                }
                val out = outputStream; val inp = inputStream
                if (out == null || inp == null || socket?.isConnected != true) {
                    throw ObdTransportDisconnectedException("Bluetooth OBD adapter is not connected")
                }
                while (inp.available() > 0) {
                    ensureSameSession()
                    if (inp.read() < 0) throw java.io.EOFException("Bluetooth OBD adapter disconnected")
                }
                ensureSameSession()
                out.write(payload); out.flush()
                val buffer = StringBuilder()
                val callbackBuffer = StringBuilder()
                val start = System.currentTimeMillis()
                var promptReceived = false
                while (System.currentTimeMillis() - start < timeoutMs) {
                    ensureSameSession()
                    if (inp.available() > 0) {
                        val received = inp.read()
                        ensureSameSession()
                        if (received < 0) throw java.io.EOFException("Bluetooth OBD adapter disconnected")
                        val char = received.toChar()
                        if (char == '>') {
                            promptReceived = true
                            if (callbackBuffer.isNotEmpty()) onChunk?.invoke(callbackBuffer.toString())
                            break
                        }
                        if (buffer.length < 1024 || onChunk == null) buffer.append(char)
                        callbackBuffer.append(char)
                        if (char == '\r' || char == '\n' || callbackBuffer.length >= 128) {
                            onChunk?.invoke(callbackBuffer.toString())
                            callbackBuffer.clear()
                        }
                    } else delay(15)
                }
                if (callbackBuffer.isNotEmpty()) onChunk?.invoke(callbackBuffer.toString())
                ensureSameSession()
                ElmResponseCompletion.requirePrompt(
                    command,
                    streaming = ElmResponseCompletion.isUnterminatedMonitorCommand(command, onChunk != null),
                    promptReceived = promptReceived
                )
                ensureSameSession()
                normalizeElmReply(buffer.toString(), command)
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failing read from a replaced socket must not cause the ViewModel
                // to disconnect the successor session after a fast reconnect.
                if (!transportSessionEpoch.isCurrent(sessionEpoch)) {
                    throw ObdSupersededSessionException(error)
                }
                throw error
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
        protocolNumber: Int = 33
    ): Result<String> = withContext(Dispatchers.IO) {
        // A malformed token, public host or incompatible CAN profile must
        // never tear down an already working Bluetooth/USB/Wi-Fi adapter.
        try {
            J2534BridgeConnectionPreflight.requireValid(
                host, port, pairingToken, requestCanId, responseCanId,
                can29Bit, protocolNumber
            )
        } catch (invalid: IllegalArgumentException) {
            return@withContext Result.failure(invalid)
        }
        val epoch = beginAdapterConnection()
        val bridge = J2534BridgeTransport(host, port, pairingToken)
        try {
            val info = bridge.connect(requestCanId, responseCanId, can29Bit, protocolNumber)
            // The TLS handshake can finish after a different adapter has been
            // selected. Never publish a stale bridge over the replacement.
            synchronized(adapterLifecycleLock) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                j2534BridgeTransport = bridge
                j2534BridgeRequestCanId = requestCanId
                j2534BridgeResponseCanId = responseCanId
                j2534BridgeProtocolNumber = protocolNumber
                activeTransport = "J2534_BRIDGE"
                isDemoMode = false
                isSimulationActive = false
            }
            Result.success(info)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            bridge.close()
            throw cancelled
        } catch (error: Exception) {
            bridge.close()
            Result.failure(error)
        }
    }

    fun createJ2534BridgeUdsClient(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int,
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 5000L
    ): UdsClient {
        val transport = j2534BridgeTransport
            ?: error("The Windows J2534 bridge is not connected.")
        require(requestCanId == j2534BridgeRequestCanId && responseCanId == j2534BridgeResponseCanId &&
            protocolNumber == j2534BridgeProtocolNumber) {
            "The J2534 bridge is configured for CAN IDs 0x${j2534BridgeRequestCanId?.toString(16)?.uppercase()} → 0x${j2534BridgeResponseCanId?.toString(16)?.uppercase()}. Disconnect and reconnect after changing the ECU CAN IDs or CAN profile."
        }
        check(activeTransport == "J2534_BRIDGE" && !isDemoMode && !isSimulationActive) {
            "J2534 UDS is disabled when no real PC bridge is connected."
        }
        return UdsClient(transport, policy, timeoutMs)
    }

    /**
     * Runs one real J2534 read-only UDS operation under the same transaction
     * lock used by ECU memory backups, identification and OBD command sessions.
     * Prevents DID/DTC reads from interrupting a two-pass physical backup.
     *
     * Both the queued session and the completed result are tied to the current
     * connection epoch, so reconnects cannot reuse an obsolete bridge.
     */
    suspend fun <T> withJ2534BridgeUdsSession(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int,
        timeoutMs: Long = 5000L,
        readDids: Set<Int> = emptySet(),
        block: suspend (UdsClient) -> T
    ): T {
        val epoch = transportSessionEpoch.capture()
        check(isJ2534BridgeConnected()) { "A real connected J2534 bridge is required." }
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534BridgeConnected()) { "J2534 bridge disconnected while waiting for UDS." }
            val client = createJ2534BridgeUdsClient(
                requestCanId, responseCanId, protocolNumber,
                UdsAccessPolicy.readOnly(readDids), timeoutMs
            )
            val result = block(client)
            transportSessionEpoch.requireCurrent(epoch)
            result
        }
    }

    /**
     * Physically acquire and archive a documented ECU memory region via the
     * connected Windows J2534 adapter. The Windows bridge independently
     * enforces its VIN-bound address allowlist; this API cannot flash an ECU.
     */
    suspend fun captureAndExportJ2534Memory(
        request: J2534LiveMemoryBackup.Request,
        output: OutputStream,
        progress: (J2534LiveMemoryBackup.CaptureProgress) -> Unit = {}
    ): J2534LiveMemoryBackup.CaptureResult {
        val epoch = transportSessionEpoch.capture()
        check(isJ2534MemoryReadAvailable()) {
            "Connect a real Windows J2534 bridge with an approved VIN-bound memory read profile."
        }
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            val bridge = j2534BridgeTransport
                ?: error("The J2534 bridge was disconnected.")
            check(isJ2534BridgeConnected() && bridge.isScopedMemoryReadEnabled) {
                "The J2534 bridge has no approved physical ECU memory read scope."
            }
            // Never allow the UI to relabel a Windows-approved address span,
            // session or region when exporting the physical acquisition.
            bridge.requireApprovedMemoryReadScope(request)
            // GM GMLAN OSID is optional: other ECUs may support scoped 0x23
            // memory reads but not legacy 0x1A identity. Never infer an OSID
            // from the VIN, adapter name, model year or an imported calibration.
            val identityBefore = try {
                bridge.readGmlanIdentification()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (unavailable: GmlanIdentityUnavailableException) {
                // A genuinely unsupported legacy identity is optional, but
                // malformed, denied or mismatched evidence is never optional.
                transportSessionEpoch.requireQueuedCurrent(epoch)
                check(bridge.isConnected && bridge.isScopedMemoryReadEnabled) {
                    "Physical J2534 bridge disconnected during GM identity discovery."
                }
                null
            }
            if (identityBefore != null) {
                require(identityBefore.vin == request.expectedVin) {
                    "Physical GM identity VIN differs from the memory-read scope."
                }
            }
            val result = try {
                J2534LiveMemoryBackup.capture(bridge, request, progress)
            } finally {
                // A reconnect can use the same VIN with a different adapter.
                transportSessionEpoch.requireQueuedCurrent(epoch)
            }
            val firmwareEvidence = identityBefore?.let { before ->
                // A second physical identity read is mandatory if the first
                // succeeded. Any OSID, VIN or responder change fails closed.
                val after = bridge.readGmlanIdentification()
                J2534LiveMemoryBackup.verifiedStableFirmware(
                    before, after, request.expectedVin
                )
            }
            // Bind the physical acquisition to the selected CAN channel and
            // approved read session. The ZIP may be opened on another device,
            // so these observations must travel with the bytes and VIN.
            val channel = EcuBackupManifest.Channel(
                requestCanId = checkNotNull(j2534BridgeRequestCanId),
                responseCanId = checkNotNull(j2534BridgeResponseCanId),
                protocolNumber = checkNotNull(j2534BridgeProtocolNumber),
                diagnosticSession = request.diagnosticSession
            )
            transportSessionEpoch.requireQueuedCurrent(epoch)
            val bound = result.copy(
                manifest = result.manifest.copy(
                    channel = channel,
                    firmwareEvidence = firmwareEvidence
                )
            )
            check(EcuBackupIntegrity.verify(
                bound.manifest, listOf(bound.capture), emptySet()
            ).consistent) {
                "Physical ECU backup evidence became inconsistent before export."
            }
            result.export(output, channel, firmwareEvidence)
            transportSessionEpoch.requireQueuedCurrent(epoch)
            bound
        }
    }

    /** Explicit low-level ECU UDS over a paired Windows J2534 adapter.
     *  The operator selects CAN addressing and payload; no extra CamaroDiag
     *  key, license or invented service-level write filter is inserted here.
     *  App feature entitlements remain enforced by its presentation layer.
     */
    suspend fun exchangeJ2534OperatorUds(
        payload: ByteArray,
        approval: EcuWriteConsentFlow.Approval,
        timeoutMs: Long = 3000L,
        isOperatorContextCurrent: () -> Boolean
    ): ByteArray {
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534BridgeConnected()) { "Connect a physical Windows J2534 bridge." }
            val reply = checkNotNull(j2534BridgeTransport).exchangeOperatorUds(
                payload, timeoutMs, approval
            ) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                EcuWriteConsentFlow.requireCurrentForTransmit(approval, isOperatorContextCurrent)
            }
            transportSessionEpoch.requireCurrent(epoch)
            reply
        }
    }

    suspend fun sendJ2534OperatorUdsOnly(
        payload: ByteArray,
        approval: EcuWriteConsentFlow.Approval,
        isOperatorContextCurrent: () -> Boolean
    ) {
        val epoch = transportSessionEpoch.capture()
        commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534BridgeConnected()) { "Connect a physical Windows J2534 bridge." }
            checkNotNull(j2534BridgeTransport).sendOperatorUdsOnly(payload, approval) {
                transportSessionEpoch.requireQueuedCurrent(epoch)
                EcuWriteConsentFlow.requireCurrentForTransmit(approval, isOperatorContextCurrent)
            }
            transportSessionEpoch.requireCurrent(epoch)
        }
    }

    suspend fun readCalibrationIdentification(): ObdEcuIdentification {
        if (!isJ2534BridgeConnected()) return withObdCommandSession { it.readEcuIdentification() }
        val epoch = transportSessionEpoch.capture()
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            val bridge = checkNotNull(j2534BridgeTransport)
            val identity = bridge.readCalibrationIdentification()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            identity
        }
    }

    /** Read GM legacy VIN/OSID on the exact active physical J2534 channel. */
    suspend fun readGmlanIdentification(): GmlanIdentityEvidence {
        val epoch = transportSessionEpoch.capture()
        check(isJ2534BridgeConnected())
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(epoch)
            check(isJ2534BridgeConnected())
            val bridge = checkNotNull(j2534BridgeTransport)
            val identity = bridge.readGmlanIdentification()
            transportSessionEpoch.requireQueuedCurrent(epoch)
            identity
        }
    }

    fun isJ2534MemoryReadAvailable(): Boolean =
        isJ2534BridgeConnected() &&
            j2534BridgeTransport?.isScopedMemoryReadEnabled == true

    fun isJ2534BridgeConnected(): Boolean =
        activeTransport == "J2534_BRIDGE" &&
            !isDemoMode && !isSimulationActive &&
            j2534BridgeTransport?.isConnected == true

    /**
     * UDS transaction over a physically connected OBDLink STN adapter.
     * STPX's hardware identification (STI + STDI) is still checked by the transport.
     *
     * Keep the adapter configuration and all UDS requests under one exclusive
     * command session. This prevents telemetry, a CAN console or a queued OBD
     * command from changing STP/filters between ISO-TP request and response.
     * A target-specific UdsAccessPolicy enables exactly the ECU operations
     * the operator's profile describes. Do not impose a global read-only
     * command filter or an additional CamaroDiag key requirement.
     * Unconfigured callers retain a non-destructive default.
     */
    suspend fun <T> withStnUdsSession(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int = 33,
        timeoutMs: Long = 3000L,
        readDids: Set<Int> = emptySet(),
        policy: UdsAccessPolicy? = null,
        block: suspend (UdsClient) -> T
    ): T {
        val requestedEpoch = transportSessionEpoch.capture()
        check(StnUdsSessionAdmission.allowsRead(
            activeAdapterCapabilities, isObdCommandChannelAvailable
        )) { "A connected physical OBDLink STN adapter with verified CAN transmit is required." }
        return commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
            check(StnUdsSessionAdmission.allowsRead(
                activeAdapterCapabilities, isObdCommandChannelAvailable
            )) { "The physical STN adapter disconnected while waiting for the UDS session." }
            val transport = StnUdsPayloadTransport(
                requestCanId = requestCanId,
                responseCanId = responseCanId,
                protocolNumber = protocolNumber
            ) { command, commandTimeout ->
                sendAtCommandInternal(command, commandTimeout, expectedEpoch = requestedEpoch)
            }
            val result = try {
                block(UdsClient(transport, policy ?: UdsAccessPolicy.readOnly(readDids), timeoutMs))
            } finally {
                // STPX changes the adapter's CAN filter, protocol and ISO-TP
                // segmentation settings. Always restore functional OBD mode
                // before releasing the mutex to telemetry or other diagnostics.
                // A replaced connection must NEVER be touched by old cleanup.
                withContext(NonCancellable) {
                    if (transportSessionEpoch.isCurrent(requestedEpoch)) {
                        val restore = StnUdsAdapterRestoration.restore { command ->
                            sendAtCommandInternal(command, 1_500L, expectedEpoch = requestedEpoch)
                        }
                        if (!restore.succeeded && transportSessionEpoch.isCurrent(requestedEpoch)) {
                            // A partially restored adapter cannot safely serve
                            // normal PID reads. Never disconnect a newer session
                            // that replaced this adapter while cleanup ran.
                            disconnect()
                            error("STN diagnostic session cleanup failed; reconnect the OBD adapter.")
                        }
                    }
                }
            }
            // A connection replaced during cleanup must not have its old
            // diagnostic bytes shown as a result of the successor session.
            transportSessionEpoch.requireCurrent(requestedEpoch)
            result
        }
    }

    /**
     * Legacy single-operation STN client; callers requiring more than one
     * exchange should use withStnUdsSession to prevent interleaved adapter setup.
     * Actual STDI validation still occurs before the first UDS command.
     */
    fun createStnUdsClient(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int = 33,
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 3000L
    ): UdsClient {
        check(StnUdsSessionAdmission.allowsRead(
            activeAdapterCapabilities, isObdCommandChannelAvailable
        )) { "A connected physical OBDLink STN adapter with verified CAN transmit is required." }
        val transport = StnUdsPayloadTransport(
            requestCanId = requestCanId,
            responseCanId = responseCanId,
            protocolNumber = protocolNumber
        ) { command, commandTimeout -> sendAtCommand(command, commandTimeout) }
        return UdsClient(transport, policy, timeoutMs)
    }

    /** Creates the standards-based read-only OBD-II engine over the currently connected ELM transport. */
    fun createObd2FeatureEngine(): Obd2FeatureEngine {
        check(isObdCommandChannelAvailable) {
            "No ELM/OBD-II command channel is available on the active transport."
        }
        return Obd2FeatureEngine(
            ObdCommandChannel { command, timeoutMs -> sendAtCommand(command, timeoutMs) }
        )
    }

    /** Creates a deterministic, clearly synthetic OBD-II channel for offline UI testing. */
    fun createSimulatedObd2FeatureEngine(): Obd2FeatureEngine =
        CamaroLfxObdSimulator.createEngine()

    fun disconnect() {
        synchronized(adapterLifecycleLock) {
            // Publish teardown before sockets are closed: in-flight commands must not
            // attach their remaining read/write steps to the next adapter session.
            transportSessionEpoch.invalidate()
            // Close every transport independently: one failing close must not leak the others.
            runCatching { usbTransport.close() }
            runCatching { wifiTransport.close() }
            runCatching { bleTransport.close() }
            runCatching { j2534BridgeTransport?.close() }
            j2534BridgeTransport = null
            j2534BridgeRequestCanId = null
            j2534BridgeResponseCanId = null
            j2534BridgeProtocolNumber = null
            runCatching { inputStream?.close() }
            runCatching { outputStream?.close() }
            runCatching { socket?.close() }

            inputStream = null
            outputStream = null
            socket = null
            activeTransport = "BLUETOOTH"
            cachedSupportedMode01Pids = null
            cachedSupportedMode01PidsAtMillis = 0L
            isDemoMode = true
            isSimulationActive = false
            activeAdapterCapabilities = null
        }
    }
}

/** Strip the adapter's echo only when it occupies the first nonempty line. */
internal fun normalizeElmReply(raw: String, command: String): String {
    val lines = raw.trim().lineSequence().iterator()
    if (!lines.hasNext()) return ""
    val first = lines.next().trim()
    return buildList {
        if (!first.equals(command.trim(), ignoreCase = true)) add(first)
        for (line in lines) if (line.isNotBlank()) add(line.trim())
    }.joinToString(" ")
}
