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
    private val supportedMode01PidsMutex = Mutex()
    @Volatile private var cachedSupportedMode01Pids: Set<Int>? = null
    @Volatile private var cachedSupportedMode01PidsAtMillis: Long = 0L
    private val usbTransport = ElmUsbTransport(context)
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
                // Retain the socket before connecting so disconnect() also closes a
                // failed RFCOMM handshake instead of leaking its descriptor.
                socket = btSocket
                btSocket.connect()
                activeTransport = "BLUETOOTH"
                inputStream = btSocket.inputStream
                outputStream = btSocket.outputStream
            }
            probeConnectedAdapter(device.name ?: "Bluetooth OBD-II", macAddress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation after cleanup: a replacement connection
            // must not race the teardown of this handshake.
            disconnect()
            throw cancelled
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation after cleanup: a replacement connection
            // must not race the teardown of this handshake.
            disconnect()
            throw cancelled
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Propagate cancellation after cleanup: a replacement connection
            // must not race the teardown of this handshake.
            disconnect()
            throw cancelled
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
            // A real 0100 response proves that the connected physical command path
            // transmitted a request and received an ECU reply; AT header support alone does not.
            val transmitProbeResp = sendAtCommand("0100", 2_500L)

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
            try {
                boundCommand("ATE0", 1_000L)
                boundCommand("ATL0", 1_000L)
                boundCommand("ATS1", 1_000L)
                boundCommand("ATH1", 1_000L)
                val formattingResponse = boundCommand("ATCAF0", 1_000L)
                check(!formattingResponse.contains("?")) {
                    "The connected adapter cannot switch to raw CAN formatting."
                }
                if (isStnAdapter) {
                    val canFilterResponse = boundCommand("ATCF 000", 1_000L)
                    val canMaskResponse = boundCommand("ATCM 000", 1_000L)
                    val filterResponse = boundCommand("STFAC", 1_000L)
                    check(
                        !canFilterResponse.contains("?") &&
                            !canMaskResponse.contains("?") &&
                            !filterResponse.contains("?")
                    ) {
                        "The STN adapter could not clear its temporary CAN filters."
                    }
                }
                val monitorResponse = boundCommand(monitorCommand, durationMs) { chunk ->
                    captured.append(chunk)
                    onChunk(chunk)
                }
                check(monitorResponse.trim() != "?") {
                    "The connected adapter does not support the selected CAN monitor command."
                }
            } finally {
                // ELM/STN monitor mode has no normal prompt; ESC returns the adapter
                // to command mode even when the caller cancels the capture.
                withContext(NonCancellable) {
                    runCatching { boundCommand("\u001B", 1_000L) }
                    if (isStnAdapter) {
                        runCatching { boundCommand("ATAR", 1_000L) }
                    }
                    // Restore the command-channel formatting established during adapter probing.
                    // A bare "AT" is not an ELM reset command; ATD would reset protocol and
                    // other settings that the app relies on, so restore each changed setting.
                    runCatching { boundCommand("ATE0", 1_000L) }
                    runCatching { boundCommand("ATL0", 1_000L) }
                    runCatching { boundCommand("ATH1", 1_000L) }
                    runCatching { boundCommand("ATS0", 1_000L) }
                    runCatching { boundCommand("ATCAF1", 1_000L) }
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
    suspend fun sendRawCanFrame(frame: RawCanFrame): String = withContext(Dispatchers.IO) {
        val requestedEpoch = transportSessionEpoch.capture()
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

        commandSessionMutex.withLock {
            transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
            check(isObdCommandChannelAvailable) { "CAN transmission connection changed while waiting." }
            suspend fun boundCommand(command: String, timeoutMs: Long): String =
                sendAtCommandInternal(command, timeoutMs, expectedEpoch = requestedEpoch)
            val protocol = if (frame.isExtended) "7" else "6"
            val header = if (frame.isExtended) {
                frame.canId.toString(16).uppercase().padStart(8, '0')
            } else {
                frame.canId.toString(16).uppercase().padStart(3, '0')
            }
            try {
                val protocolResponse = boundCommand("ATSP$protocol", 1_500L)
                check(!protocolResponse.contains("?")) {
                    "The adapter rejected the selected CAN protocol."
                }
                val formattingResponse = boundCommand("ATCAF0", 1_500L)
                check(!formattingResponse.contains("?")) {
                    "The adapter cannot enter raw CAN formatting mode."
                }
                val headerResponse = boundCommand("ATSH $header", 1_500L)
                check(!headerResponse.contains("?")) {
                    "The adapter rejected CAN header $header."
                }
                val headersResponse = boundCommand("ATH1", 1_000L)
                val spacesResponse = boundCommand("ATS1", 1_000L)
                check(!headersResponse.contains("?") && !spacesResponse.contains("?")) {
                    "The adapter cannot expose received CAN identifiers and byte boundaries."
                }
                val payload = frame.data.joinToString(" ") {
                    "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF)
                }
                val frameResponse = boundCommand(payload, 4_000L)
                check(!frameResponse.contains("?")) {
                    "The adapter rejected the raw CAN frame."
                }
                frameResponse
            } finally {
                // Return to the app's configured 11-bit HS-CAN / automatic-format state.
                withContext(NonCancellable) {
                    runCatching { boundCommand("ATSP6", 1_500L) }
                    // Restore the app's standard functional OBD request header as well
                    // as formatting; otherwise later requests could remain physically addressed.
                    runCatching { boundCommand("ATSH 7DF", 1_500L) }
                    runCatching { boundCommand("ATCAF1", 1_500L) }
                    runCatching { boundCommand("ATH1", 1_000L) }
                    runCatching { boundCommand("ATS0", 1_000L) }
                }
            }
        }
    }

    suspend fun sendAtCommand(command: String, timeoutMs: Long = 1200L): String {
        val requestedEpoch = transportSessionEpoch.capture()
        return withContext(Dispatchers.IO) {
            commandSessionMutex.withLock {
                transportSessionEpoch.requireQueuedCurrent(requestedEpoch)
                // Adapter probing calls this before capability detection has cleared
                // demo mode. The epoch check is valid here; a channel-available
                // check would incorrectly reject every initial ATZ/0100 probe.
                sendAtCommandInternal(command, timeoutMs, expectedEpoch = requestedEpoch)
            }
        }
    }

    private suspend fun sendAtCommandInternal(
        command: String,
        timeoutMs: Long,
        expectedEpoch: Long? = null,
        onChunk: ((String) -> Unit)? = null
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
                ElmResponseCompletion.requirePrompt(command, streaming = onChunk != null, promptReceived = promptReceived)
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
                ElmResponseCompletion.requirePrompt(command, streaming = onChunk != null, promptReceived = promptReceived)
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
                ElmResponseCompletion.requirePrompt(command, streaming = onChunk != null, promptReceived = promptReceived)
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
                ElmResponseCompletion.requirePrompt(command, streaming = onChunk != null, promptReceived = promptReceived)
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
        disconnect()
        val bridge = J2534BridgeTransport(host, port, pairingToken)
        try {
            val info = bridge.connect(requestCanId, responseCanId, can29Bit, protocolNumber)
            j2534BridgeTransport = bridge
            j2534BridgeRequestCanId = requestCanId
            j2534BridgeResponseCanId = responseCanId
            j2534BridgeProtocolNumber = protocolNumber
            activeTransport = "J2534_BRIDGE"
            isDemoMode = false
            isSimulationActive = false
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
            val result = try {
                J2534LiveMemoryBackup.capture(bridge, request, progress)
            } finally {
                // A reconnect can use the same VIN with a different adapter.
                transportSessionEpoch.requireQueuedCurrent(epoch)
            }
            result.export(output)
            transportSessionEpoch.requireQueuedCurrent(epoch)
            result
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
     * Read-only UDS transaction over a physically connected OBDLink STN adapter.
     * STPX's hardware identification (STI + STDI) is still checked by the transport.
     *
     * Keep the adapter configuration and all UDS requests under one exclusive
     * command session. This prevents telemetry, a CAN console or a queued OBD
     * command from changing STP/filters between ISO-TP request and response.
     * Only the documented UDS/ISO-TP transport is used; this is not ECU flashing.
     */
    suspend fun <T> withStnUdsSession(
        requestCanId: Int,
        responseCanId: Int,
        protocolNumber: Int = 33,
        timeoutMs: Long = 3000L,
        readDids: Set<Int> = emptySet(),
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
                block(UdsClient(transport, UdsAccessPolicy.readOnly(readDids), timeoutMs))
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
