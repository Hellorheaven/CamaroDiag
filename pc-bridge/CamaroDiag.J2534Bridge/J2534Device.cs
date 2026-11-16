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

    public J2534Device(J2534Adapter adapter) => _adapter = adapter;

    public string AdapterName => _adapter.Name;
    public string DriverPath => _adapter.FunctionLibrary;
    public bool IsOpen => _opened;

    public void Open(int requestCanId, int responseCanId, bool can29Bit, int protocolNumber)
    {
        if (_opened) Close();
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
            if (message.DataSize < 5 || message.DataSize > MaxData) continue;
            var canId = ReadCanId(message.Data);
            if (canId != _responseCanId) continue;
            var response = message.Data.Skip(4).Take((int)message.DataSize - 4).ToArray();
            if (response.Length >= 3 && response[0] == 0x7F && response[1] == payload[0] && response[2] == 0x78)
            {
                if (++pendingResponses > 8) throw new TimeoutException("ECU returned too many UDS ResponsePending (0x78) replies.");
                continue;
            }
            return response;
        }

        throw new TimeoutException($"No ISO15765 response from CAN ID 0x{_responseCanId:X} within {timeoutMs} ms.");
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

    private static void ValidateReadOnlyService(byte[] payload, bool allowNoResponse)
    {
        var service = payload[0];
        if (service == 0x22 || service == 0x19) return;
        if (service == 0x3E && payload.Length >= 2 && payload[1] is 0x00 or 0x80) return;
        throw new UnauthorizedAccessException(
            $"PC bridge is read-only. UDS service 0x{service:X2} is not enabled. " +
            "Only ReadDataByIdentifier (0x22), ReadDTCInformation (0x19) and TesterPresent (0x3E) are accepted.");
    }

    private PassThruMsg CreatePayloadMessage(int canId, byte[] payload)
    {
        var message = CreateCanIdMessage(canId, _can29Bit);
        Buffer.BlockCopy(payload, 0, message.Data, 4, payload.Length);
        message.DataSize = (uint)(4 + payload.Length);
        message.TxFlags |= Iso15765FramePad;
        return message;
    }

    private static PassThruMsg CreateCanIdMessage(int canId, bool can29Bit, uint mask = 0)
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

    private static PassThruMsg NewMessage() => new()
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
