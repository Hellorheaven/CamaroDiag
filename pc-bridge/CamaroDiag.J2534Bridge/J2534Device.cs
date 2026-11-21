using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Minimal SAE J2534-1 PassThru provider for ISO 15765-2 CAN.
/// The installed adapter vendor DLL remains responsible for hardware access and timing.
/// </summary>
internal sealed class J2534Device : IDisposable
{
    private const uint ProtocolIso15765 = 6;
    private const uint ProtocolSwIso15765 = 0x8007;
    private const uint FlowControlFilter = 3;
    private const uint StatusNoError = 0;
    private const uint ErrorTimeout = 0x09;
    private const uint ErrorBufferEmpty = 0x10;
    private const uint Can29BitId = 0x100;
    private const uint Iso15765FramePad = 0x40;
    private const uint NonPayloadRxStatus = 0x1F; // SAE J2534-1 04.04: echo, start, break, transmit, padding error
    private const uint Iso15765ExtendedAddress = 0x80;
    private const int MaxData = 4128;

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct PassThruMsg
    {
        public uint ProtocolId;
        public uint RxStatus;
        public uint TxFlags;
        public uint Timestamp;
        public uint DataSize;
        public uint ExtraDataIndex;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = MaxData)]
        public byte[] Data;
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruOpenDelegate(IntPtr name, out uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruCloseDelegate(uint deviceId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruConnectDelegate(uint deviceId, uint protocolId, uint flags, uint baudRate, out uint channelId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruDisconnectDelegate(uint channelId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruStartMsgFilterDelegate(uint channelId, uint filterType,
        ref PassThruMsg mask, ref PassThruMsg pattern, ref PassThruMsg flowControl, out uint filterId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruStopMsgFilterDelegate(uint channelId, uint filterId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruWriteMsgsDelegate(uint channelId, [In] PassThruMsg[] messages, ref uint count, uint timeout);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint PassThruReadMsgsDelegate(uint channelId, [In, Out] PassThruMsg[] messages, ref uint count, uint timeout);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet = CharSet.Ansi)]
    private delegate uint PassThruGetLastErrorDelegate(StringBuilder description);

    private readonly J2534Adapter _adapter;
    private readonly J2534MemoryReadScope? _memoryReadScope;
    private bool _memoryReadVinVerified;
    private IntPtr _library;
    private PassThruOpenDelegate? _open;
    private PassThruCloseDelegate? _close;
    private PassThruConnectDelegate? _connect;
    private PassThruDisconnectDelegate? _disconnect;
    private PassThruStartMsgFilterDelegate? _startFilter;
    private PassThruStopMsgFilterDelegate? _stopFilter;
    private PassThruWriteMsgsDelegate? _write;
    private PassThruReadMsgsDelegate? _read;
    private PassThruGetLastErrorDelegate? _getLastError;
    private uint _deviceId;
    private uint _channelId;
    private uint _protocolId = ProtocolIso15765;
    private uint? _filterId;
    private int _requestCanId;
    private int _responseCanId;
    private bool _can29Bit;
    private bool _deviceOpened;
    private bool _channelConnected;
    private bool _opened;

    public J2534Device(J2534Adapter adapter, J2534MemoryReadScope? memoryReadScope = null)
    {
        _adapter = adapter;
        _memoryReadScope = memoryReadScope;
    }

    public string AdapterName => _adapter.Name;
    public string DriverPath => _adapter.FunctionLibrary;
    public bool IsOpen => _opened;
    public string ResponseCanIdLabel => _responseCanId.ToString(_can29Bit ? "X8" : "X3");
    public bool IsMemoryReadEnabled => _memoryReadScope != null && _memoryReadVinVerified;

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ReadVoltageIoctl(uint deviceId, uint ioctlId, IntPtr input, out uint millivolts);

    public uint? ReadBatteryMillivolts()
    {
        EnsureOpen();
        if (!NativeLibrary.TryGetExport(_library, "PassThruIoctl", out var entry)) return null;
        var ioctl = Marshal.GetDelegateForFunctionPointer<ReadVoltageIoctl>(entry);
        const uint readVBatt = 0x03;
        return ioctl(_deviceId, readVBatt, IntPtr.Zero, out var value) == StatusNoError ? value : null;
    }

    public void Open(int requestCanId, int responseCanId, bool can29Bit, int protocolNumber)
    {
        if (_opened) Close();
        _memoryReadVinVerified = false;
        if (_memoryReadScope != null &&
            !_memoryReadScope.MatchesChannel(requestCanId, responseCanId, protocolNumber))
            throw new UnauthorizedAccessException(
                "Configured CAN IDs and protocol do not match the VIN-bound memory read profile.");
        var isSwCan = protocolNumber is 63 or 64;
        var maxId = can29Bit ? 0x1FFFFFFF : 0x7FF;
        var baudRate = protocolNumber switch
        {
            33 or 34 => 500000,
            35 or 36 => 250000,
            63 or 64 => 62500,
            _ => throw new ArgumentOutOfRangeException(nameof(protocolNumber), "Supported profiles: 33-36 HS-CAN, 63-64 SW-CAN.")
        };
        _protocolId = isSwCan ? ProtocolSwIso15765 : ProtocolIso15765;
        var expects29Bit = protocolNumber is 34 or 36 or 64;
        if (can29Bit != expects29Bit)
            throw new ArgumentException("CAN profile 33/35/63 requires 11-bit IDs; profile 34/36/64 requires 29-bit IDs.");
        if (requestCanId < 0 || responseCanId < 0 || requestCanId > maxId || responseCanId > maxId ||
            requestCanId == responseCanId)
            throw new ArgumentOutOfRangeException(nameof(requestCanId), "CAN identifiers do not match the selected 11/29-bit format.");
        _requestCanId = requestCanId;
        _responseCanId = responseCanId;
        _can29Bit = can29Bit;

        try
        {
            _library = NativeLibrary.Load(_adapter.FunctionLibrary);
            _open = Load<PassThruOpenDelegate>("PassThruOpen");
            _close = Load<PassThruCloseDelegate>("PassThruClose");
            _connect = Load<PassThruConnectDelegate>("PassThruConnect");
            _disconnect = Load<PassThruDisconnectDelegate>("PassThruDisconnect");
            _startFilter = Load<PassThruStartMsgFilterDelegate>("PassThruStartMsgFilter");
            _stopFilter = Load<PassThruStopMsgFilterDelegate>("PassThruStopMsgFilter");
            _write = Load<PassThruWriteMsgsDelegate>("PassThruWriteMsgs");
            _read = Load<PassThruReadMsgsDelegate>("PassThruReadMsgs");
            if (NativeLibrary.TryGetExport(_library, "PassThruGetLastError", out var lastError))
                _getLastError = Marshal.GetDelegateForFunctionPointer<PassThruGetLastErrorDelegate>(lastError);

            Check(_open(IntPtr.Zero, out _deviceId), "PassThruOpen");
            _deviceOpened = true;
            var flags = can29Bit ? Can29BitId : 0u;
            Check(_connect(_deviceId, _protocolId, flags, (uint)baudRate, out _channelId),
                isSwCan ? "PassThruConnect(SW_ISO15765_PS)" : "PassThruConnect(ISO15765)");
            _channelConnected = true;

            var mask = CreateCanIdMessage(maxId, can29Bit, 0xFFFFFFFFu);
            var pattern = CreateCanIdMessage(_responseCanId, can29Bit);
            var flowControl = CreateCanIdMessage(_requestCanId, can29Bit);
            Check(_startFilter(_channelId, FlowControlFilter, ref mask, ref pattern, ref flowControl, out var filter),
                "PassThruStartMsgFilter(FLOW_CONTROL_FILTER)");
            _filterId = filter;
            _opened = true;
            if (_memoryReadScope != null)
            {
                // Read live VIN via an already allowed, non-destructive DID before
                // exposing any memory command. No identity -> no 0x23 permission.
                var vinResponse = Exchange(new byte[] { 0x22, 0xF1, 0x90 }, 6000);
                if (vinResponse.Length < 20 || vinResponse[0] != 0x62 ||
                    vinResponse[1] != 0xF1 || vinResponse[2] != 0x90 ||
                    !string.Equals(Encoding.ASCII.GetString(vinResponse, 3, 17),
                        _memoryReadScope.ExpectedVin, StringComparison.OrdinalIgnoreCase))
                    throw new UnauthorizedAccessException(
                        "The live ECU VIN does not match the approved memory read profile.");
                _memoryReadVinVerified = true;
            }
        }
        catch
        {
            Close();
            throw;
        }
    }

    public byte[] Exchange(byte[] payload, int timeoutMs)
    {
        EnsureOpen();
        if (payload.Length is < 1 or > 4091) throw new ArgumentOutOfRangeException(nameof(payload));
        ValidateReadOnlyService(payload, allowNoResponse: false);

        return ExchangeCore(payload, timeoutMs, nativeBenchReply: false);
    }

    // The future isolated bench caller uses a separate reply dialect.
    // The public network exchange above always remains read-only.
    private byte[] ExchangeCore(byte[] payload, int timeoutMs, bool nativeBenchReply)
    {
        var tx = CreatePayloadMessage(_requestCanId, payload);
        var messages = new[] { tx };
        uint count = 1;
        Check(_write!(_channelId, messages, ref count, (uint)Math.Clamp(timeoutMs, 100, 10000)), "PassThruWriteMsgs");
        if (count != 1) throw new IOException("J2534 driver did not queue the UDS request.");

        var deadline = Stopwatch.GetTimestamp() + (long)(Math.Clamp(timeoutMs, 100, 10000) / 1000.0 * Stopwatch.Frequency);
        var pendingResponses = 0;
        while (Stopwatch.GetTimestamp() < deadline)
        {
            var received = new[] { NewMessage() };
            uint receivedCount = 1;
            var remainingMs = (int)Math.Clamp((deadline - Stopwatch.GetTimestamp()) * 1000.0 / Stopwatch.Frequency, 1, 250);
            var status = _read!(_channelId, received, ref receivedCount, (uint)remainingMs);
            if (status == ErrorBufferEmpty || status == ErrorTimeout ||
                (status == StatusNoError && receivedCount == 0)) continue;
            Check(status, "PassThruReadMsgs");
            if (receivedCount == 0) continue;

            var message = received[0];
            var response = DecodeReceivedPayload(message.ProtocolId, message.RxStatus,
                message.DataSize, message.ExtraDataIndex, message.Data,
                _protocolId, _responseCanId, payload, nativeBenchReply);
            if (response is null) continue;
            if (response.Length >= 3 && response[0] == 0x7F && response[1] == payload[0] && response[2] == 0x78)
            {
                if (++pendingResponses > 8) throw new TimeoutException("ECU returned too many UDS ResponsePending (0x78) replies.");
                continue;
            }
            return response;
        }

        throw new TimeoutException($"No ISO15765 response from CAN ID 0x{_responseCanId:X} within {timeoutMs} ms.");
    }

    // A PassThru indication or stale response must never count as an ECU ACK.
    // This validates the standard J2534-1 receive framing, not ECU flash support.
    internal static byte[]? DecodeReceivedPayload(uint protocol, uint rxStatus,
        uint dataSize, uint extraDataIndex, byte[] data,
        uint expectedProtocol, int responseId, byte[] request, bool nativeBenchReply = false)
    {
        if (request.Length == 0 || protocol != expectedProtocol ||
            (rxStatus & (NonPayloadRxStatus | Iso15765ExtendedAddress)) != 0 ||
            dataSize < 5 || dataSize > MaxData || dataSize > data.Length ||
            ReadCanId(data) != responseId)
            return null;
        // Some vendor DLLs use zero when no extra bytes are attached.
        // Reject unexpected trailers rather than hashing or interpreting them.
        if (extraDataIndex != 0 && extraDataIndex != dataSize)
            return null;
        var response = data.AsSpan(4, checked((int)dataSize) - 4);
        if (response[0] == 0x7F)
            return response.Length == 3 && response[1] == request[0] ? response.ToArray() : null;
        if (nativeBenchReply) return NativeE39BenchReply.Matches(request, response)
            ? response.ToArray() : null;
        if (response[0] != unchecked((byte)(request[0] + 0x40))) return null;

        // Match echoed identifiers on the read services used by the bridge.
        // A response from the right CAN ID but another DID is stale.
        var echoLength = request[0] switch
        {
            0x09 or 0x1A => 1,
            0x22 => 2,
            0x10 or 0x19 or 0x3E => 1,
            _ => 0
        };
        if (echoLength > 0 &&
            (request.Length <= echoLength || response.Length <= echoLength))
            return null;
        for (var i = 1; i <= echoLength; i++)
        {
            var expected = request[0] is 0x10 or 0x19 or 0x3E
                ? (byte)(request[i] & 0x7F) : request[i];
            if (response[i] != expected) return null;
        }
        return response.ToArray();
    }

    /// <summary>
    /// Physical, read-only GM CAN identity: GMLAN service 1A rather than
    /// standard UDS 22. This does not open any programming privilege.
    /// </summary>
    public NativeGmlanIdentification.Identity ReadGmlanIdentification()
    {
        EnsureOpen();
        byte[] Read(byte did, bool required)
        {
            try
            {
                return Exchange(NativeGmlanIdentification.BuildRequest(did), 1500);
            }
            catch (TimeoutException) when (!required)
            {
                // Optional OSID candidate was silent; try the next
                // documented GMLAN software-module identifier.
                return [0x7F, 0x1A, 0x31];
            }
        }
        return NativeGmlanIdentification.Compose(
            Read(NativeGmlanIdentification.VinDid, required: true),
            Read(NativeGmlanIdentification.OsModuleDid, required: false),
            Read(NativeGmlanIdentification.OsFallbackDid, required: false),
            physicalRead: true);
    }

    public object ReadCalibrationIdentification()
    {
        EnsureOpen();
        IReadOnlyList<string> Read(byte pid)
        {
            try
            {
                return DecodeIdentityRecords(pid, Exchange(new byte[] { 0x09, pid }, 1500));
            }
            catch (TimeoutException)
            {
                // Optional information types may receive no reply.
                return Array.Empty<string>();
            }
        }

        var vin = Read(0x02).SingleOrDefault();
        var calibrationIds = Read(0x04);
        var cvns = Read(0x06);
        var ecuNames = Read(0x0A);
        if (vin == null && calibrationIds.Count + cvns.Count + ecuNames.Count == 0)
            throw new IOException("The selected ECU supplied no Mode 09 identity records.");
        return new { vin, calibrationIds, cvns, ecuNames,
            responseCanId = _responseCanId.ToString(_can29Bit ? "X8" : "X3") };
    }

    internal static IReadOnlyList<string> DecodeIdentityRecords(byte pid, byte[] reply)
    {
        int width = pid switch { 0x02 => 17, 0x04 => 16, 0x06 => 4, 0x0A => 20,
            _ => throw new ArgumentOutOfRangeException(nameof(pid)) };
        if (reply.Length == 3 && reply[0] == 0x7F && reply[1] == 0x09)
            return Array.Empty<string>();
        if (reply.Length < 3 || reply[0] != 0x49 || reply[1] != pid ||
            reply.Length != 3 + reply[2] * width || (pid == 2 && reply[2] != 1))
            throw new InvalidDataException("Malformed Mode 09 identity response.");

        var records = new List<string>();
        for (int i = 3; i < reply.Length; i += width)
        {
            var field = reply.AsSpan(i, width);
            if (pid == 0x06)
            {
                records.Add(Convert.ToHexString(field));
                continue;
            }

            // ECU names and CAL IDs can have space or NUL padding at either end.
            var text = Encoding.Latin1.GetString(field).Trim(' ', '\0');
            if (text.Any(c => c < ' ' || c > '~') ||
                (pid == 2 && (text.Length != 17 || text.Any(c =>
                    !(c is >= '0' and <= '9' or >= 'A' and <= 'Z') || c is 'I' or 'O' or 'Q'))))
                throw new InvalidDataException("Invalid ECU identity text.");
            if (text.Length != 0) records.Add(text);
        }
        return records;
    }

    public void SendOnly(byte[] payload)
    {
        EnsureOpen();
        if (payload.Length is < 1 or > 4091) throw new ArgumentOutOfRangeException(nameof(payload));
        ValidateReadOnlyService(payload, allowNoResponse: true);
        var messages = new[] { CreatePayloadMessage(_requestCanId, payload) };
        uint count = 1;
        Check(_write!(_channelId, messages, ref count, 1000), "PassThruWriteMsgs");
        if (count != 1) throw new IOException("J2534 driver did not queue the UDS message.");
    }

    private void ValidateReadOnlyService(byte[] payload, bool allowNoResponse)
    {
        var service = payload[0];
        if (!allowNoResponse && payload.Length == 2 && service == 0x09 &&
            payload[1] is 0x02 or 0x04 or 0x06 or 0x0A) return;
        if (!allowNoResponse && payload.Length == 2 && service == 0x1A &&
            payload[1] is 0x90 or 0xC1 or 0xC9) return;
        if (service == 0x22 || service == 0x19) return;
        if (service == 0x3E && payload.Length == 2 && payload[1] is 0x00 or 0x80) return;
        if (!allowNoResponse && _memoryReadVinVerified && _memoryReadScope != null)
        {
            if (service == 0x23 && _memoryReadScope.AllowsReadMemory(payload)) return;
            if (service == 0x10 && _memoryReadScope.AllowsSessionControl(payload)) return;
        }
        throw new UnauthorizedAccessException(
            $"PC bridge denied UDS service 0x{service:X2}: " +
            "writes and security access are permanently blocked; memory reads require " +
            "the operator's approved address range, channel and matching live VIN.");
    }

    private PassThruMsg CreatePayloadMessage(int canId, byte[] payload)
    {
        var message = CreateCanIdMessage(canId, _can29Bit);
        Buffer.BlockCopy(payload, 0, message.Data, 4, payload.Length);
        message.DataSize = (uint)(4 + payload.Length);
        message.TxFlags |= Iso15765FramePad;
        return message;
    }

    private PassThruMsg CreateCanIdMessage(int canId, bool can29Bit, uint mask = 0)
    {
        var data = new byte[MaxData];
        var id = (uint)canId;
        data[0] = (byte)(id >> 24);
        data[1] = (byte)(id >> 16);
        data[2] = (byte)(id >> 8);
        data[3] = (byte)id;
        if (mask != 0)
        {
            data[0] = 0xFF; data[1] = 0xFF; data[2] = 0xFF; data[3] = 0xFF;
        }
        return new PassThruMsg
        {
            ProtocolId = _protocolId,
            TxFlags = can29Bit ? Can29BitId : 0u,
            DataSize = 4,
            Data = data
        };
    }

    private PassThruMsg NewMessage() => new()
    {
        ProtocolId = _protocolId,
        Data = new byte[MaxData]
    };

    private static int ReadCanId(byte[] data) =>
        (int)(((uint)data[0] << 24) | ((uint)data[1] << 16) | ((uint)data[2] << 8) | data[3]);

    private T Load<T>(string export) where T : Delegate =>
        Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(_library, export));

    private void EnsureOpen()
    {
        if (!_opened) throw new InvalidOperationException("J2534 channel is not open. Connect the PC bridge first.");
    }

    private void Check(uint status, string operation)
    {
        if (status == StatusNoError) return;
        var detail = new StringBuilder(256);
        try { _getLastError?.Invoke(detail); } catch { }
        var text = detail.ToString();
        throw new IOException($"{operation} failed with J2534 status 0x{status:X8}" +
            (string.IsNullOrWhiteSpace(text) ? "." : $": {text}"));
    }

    public void Close()
    {
        if (_channelConnected)
        {
            if (_filterId.HasValue) try { _stopFilter?.Invoke(_channelId, _filterId.Value); } catch { }
            try { _disconnect?.Invoke(_channelId); } catch { }
        }
        if (_deviceOpened) try { _close?.Invoke(_deviceId); } catch { }
        _filterId = null;
        _channelId = 0;
        _deviceId = 0;
        _deviceOpened = false;
        _channelConnected = false;
        _opened = false;
        _memoryReadVinVerified = false;
        if (_library != IntPtr.Zero)
        {
            try { NativeLibrary.Free(_library); } catch { }
            _library = IntPtr.Zero;
        }
        _open = null; _close = null; _connect = null; _disconnect = null;
        _startFilter = null; _stopFilter = null; _write = null; _read = null; _getLastError = null;
    }

    public void Dispose() => Close();
}
