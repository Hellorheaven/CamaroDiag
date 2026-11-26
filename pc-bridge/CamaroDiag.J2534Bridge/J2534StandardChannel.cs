using System.Runtime.InteropServices;
using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Bidirectional SAE J2534-1/2 PassThru channel. PassThruConnect,
/// PassThruWriteMsgs, PassThruReadMsgs, flow-control and K-Line init are
/// delegated to the REAL vendor DLL. No invented protocol restrictions on
/// diagnostic service bytes: OEM and ECU validate each actual request.
/// Caller must obtain fresh operator consent for mutating transactions.
/// </summary>
internal sealed class J2534StandardChannel : IDisposable
{
    internal const uint J1850Vpw = 0x01;
    internal const uint J1850Pwm = 0x02;
    internal const uint Iso9141 = 0x03;
    internal const uint Iso14230 = 0x04;
    internal const uint Can = 0x05;
    internal const uint Iso15765 = 0x06;
    internal const uint SwIso15765 = 0x8007; // SAE J2534-2 optional single-wire ISO-TP
    internal const uint SwCan = 0x8008; // SAE J2534-2 optional SAE J2411/SW-CAN
    internal const uint FdCan = 0x8011; // SAE J2534-2 extended IDs, requires vendor support
    internal const uint FdIso15765 = 0x8012;
    internal const uint FiveBaudInitIoctl = 0x04;
    internal const uint FastInitIoctl = 0x05;
    internal const uint KlineChecksumDisabled = 0x0000_0200; // J2534 ISO9141_NO_CHECKSUM / CHECKSUM_DISABLED
    internal const uint PassFilter = 0x01;
    internal const uint FlowControlFilter = 0x03;
    internal const uint RxTxEcho = 0x01;
    internal const uint RxStartOfMessage = 0x02;
    internal const uint RxBreak = 0x04;
    internal const uint RxTxIndication = 0x08;
    internal const uint RxPaddingError = 0x10;
    internal const int MaxData = 4128;

    internal static bool IsAuxiliaryReceiveStatus(uint status) =>
        (status & (RxTxEcho | RxStartOfMessage |
            RxBreak | RxTxIndication | RxPaddingError)) != 0;

    internal static bool IsKnownProtocol(uint id) => id is J1850Vpw or J1850Pwm
        or Iso9141 or Iso14230 or Can or Iso15765 or
            SwIso15765 or SwCan or FdCan or FdIso15765;

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    private struct Msg
    {
        public uint ProtocolId, RxStatus, TxFlags, Timestamp, DataSize, ExtraDataIndex;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = MaxData)]
        public byte[] Data;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SByteArray
    {
        public uint NumOfBytes;
        public IntPtr BytePtr;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SConfig
    {
        public uint Parameter, Value;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SConfigList
    {
        public uint NumOfParams;
        public IntPtr ConfigPtr;
    }

    internal const uint SetConfigIoctl = 0x02;
    internal const uint FdCanDataPhaseRate = 0x805C;
    internal const uint SwCanHighSpeedDataRate = 0x8010;

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint OpenFn(IntPtr name, out uint device);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint CloseFn(uint device);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ConnectFn(uint device, uint protocol, uint flags,
        uint baud, out uint channel);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint DisconnectFn(uint channel);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint WriteFn(uint channel, [In] Msg[] messages,
        ref uint count, uint timeoutMs);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ReadFn(uint channel, [In, Out] Msg[] messages,
        ref uint count, uint timeoutMs);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint StartFilterFn(uint channel, uint filter,
        ref Msg mask, ref Msg pattern, IntPtr flow, out uint filterId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint StopFilterFn(uint channel, uint filterId);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint IoctlFn(uint channel, uint ioctl, IntPtr input, IntPtr output);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, CharSet = CharSet.Ansi)]
    private delegate uint ErrorFn(StringBuilder description);

    private IntPtr library;
    private OpenFn? open;
    private CloseFn? close;
    private ConnectFn? connect;
    private DisconnectFn? disconnect;
    private WriteFn? write;
    private ReadFn? read;
    private StartFilterFn? startFilter;
    private StopFilterFn? stopFilter;
    private IoctlFn? ioctl;
    private ErrorFn? lastError;
    private uint deviceId, channelId;
    private uint protocolId;
    private uint? filterId;
    private bool deviceOpened, channelOpened;

    internal uint ProtocolId => protocolId;

    internal static J2534StandardChannel Connect(J2534Adapter adapter, uint id,
        uint baudRate, uint flags = 0)
    {
        ArgumentNullException.ThrowIfNull(adapter);
        if (!IsKnownProtocol(id) || baudRate == 0)
            throw new ArgumentException("A valid SAE J2534 protocol ID and baud rate are required.");
        var channel = new J2534StandardChannel();
        try
        {
            channel.library = NativeLibrary.Load(adapter.FunctionLibrary);
            channel.open = channel.Get<OpenFn>("PassThruOpen");
            channel.close = channel.Get<CloseFn>("PassThruClose");
            channel.connect = channel.Get<ConnectFn>("PassThruConnect");
            channel.disconnect = channel.Get<DisconnectFn>("PassThruDisconnect");
            channel.write = channel.Get<WriteFn>("PassThruWriteMsgs");
            channel.read = channel.Get<ReadFn>("PassThruReadMsgs");
            channel.startFilter = channel.Get<StartFilterFn>("PassThruStartMsgFilter");
            channel.stopFilter = channel.Get<StopFilterFn>("PassThruStopMsgFilter");
            channel.ioctl = channel.Get<IoctlFn>("PassThruIoctl");
            if (NativeLibrary.TryGetExport(channel.library,
                "PassThruGetLastError", out var pointer))
                channel.lastError = Marshal.GetDelegateForFunctionPointer<ErrorFn>(pointer);
            channel.Require(channel.open(IntPtr.Zero, out channel.deviceId), "PassThruOpen");
            channel.deviceOpened = true;
            channel.Require(channel.connect(channel.deviceId, id,
                flags, baudRate, out channel.channelId), "PassThruConnect");
            channel.channelOpened = true;
            channel.protocolId = id;
            return channel;
        }
        catch
        {
            channel.Dispose();
            throw;
        }
    }

    /// <summary>
    /// Raw PassThru API Data bytes include the appropriate protocol header
    /// (e.g. J1850/K-Line 3-byte address header or CAN 4-byte ID). The vendor
    /// driver handles physical encoding and physical timing, not this class.
    /// </summary>
    internal void Transmit(byte[] passThruData, uint txFlags = 0, int timeoutMs = 1000)
    {
        EnsureConnected();
        if (passThruData is null || passThruData.Length is < 1 or > MaxData ||
            timeoutMs is < 1 or > 30000)
            throw new ArgumentOutOfRangeException(nameof(passThruData));
        var message = MakeMsg(passThruData, txFlags);
        var send = new[] { message };
        uint count = 1;
        Require(write!(channelId, send, ref count, (uint)timeoutMs), "PassThruWriteMsgs");
        if (count != 1) throw new IOException("Vendor J2534 driver queued no frame.");
    }

    internal sealed record Frame(uint ProtocolId, uint RxStatus, uint Timestamp,
        byte[] Data);

    internal Frame? Receive(int timeoutMs = 1000)
    {
        EnsureConnected();
        if (timeoutMs is < 1 or > 30000)
            throw new ArgumentOutOfRangeException(nameof(timeoutMs));
        // PassThruReadMsgs may yield TX echoes, START_OF_MESSAGE or vendor
        // status indications before the ECU's actual RX. Keep reading
        // under one bounded deadline instead of confusing those with a
        // genuine receive timeout.
        var clock = System.Diagnostics.Stopwatch.StartNew();
        while (clock.ElapsedMilliseconds < timeoutMs)
        {
            var received = new[] { MakeMsg([], 0) };
            uint count = 1;
            var remaining = (uint)Math.Max(1,
                timeoutMs - clock.ElapsedMilliseconds);
            var result = read!(channelId, received, ref count, remaining);
            if (result is 0x09 or 0x10 ||
                (result == 0 && count == 0))
                return null;
            Require(result, "PassThruReadMsgs");
            if (count != 1) continue;
            var frame = received[0];
            if (IsAuxiliaryReceiveStatus(frame.RxStatus))
                continue;
            if (frame.ProtocolId != protocolId ||
                frame.Data is null || frame.DataSize > MaxData ||
                frame.DataSize > frame.Data.Length ||
                (frame.ExtraDataIndex != 0 &&
                 frame.ExtraDataIndex != frame.DataSize))
                throw new InvalidDataException("Inconsistent J2534 receive frame.");
            return new Frame(frame.ProtocolId, frame.RxStatus,
                frame.Timestamp,
                frame.Data.AsSpan(0, (int)frame.DataSize).ToArray());
        }
        return null;
    }

    /// <summary>Discard queued receive data before a new correlated request.
    /// A driver that cannot perform CLEAR_RX_BUFFER is not suitable for
    /// this independent legacy evidence campaign.</summary>
    internal void ClearReceiveQueue()
    {
        EnsureConnected();
        Require(ioctl!(channelId, 0x08, IntPtr.Zero, IntPtr.Zero),
            "PassThruIoctl(CLEAR_RX_BUFFER)");
    }

    /// <summary>Install native filter; flowControlData is required for ISO-TP.</summary>
    internal void ConfigureFilter(byte[] maskData, byte[] patternData,
        byte[]? flowControlData = null, uint txFlags = 0)
    {
        EnsureConnected();
        if (maskData is null || patternData is null ||
            maskData.Length is < 1 or > 12 ||
            maskData.Length != patternData.Length ||
            (flowControlData != null && flowControlData.Length != maskData.Length))
            throw new ArgumentException("J2534 filter mask/pattern/header lengths differ.");
        if (filterId.HasValue) Require(stopFilter!(channelId, filterId.Value),
            "PassThruStopMsgFilter");
        filterId = null;
        var mask = MakeMsg(maskData, txFlags);
        var pattern = MakeMsg(patternData, txFlags);
        var flowPtr = IntPtr.Zero;
        try
        {
            if (flowControlData != null)
            {
                flowPtr = Marshal.AllocHGlobal(Marshal.SizeOf<Msg>());
                Marshal.StructureToPtr(MakeMsg(flowControlData, txFlags),
                    flowPtr, false);
            }
            Require(startFilter!(channelId,
                flowControlData is null ? PassFilter : FlowControlFilter,
                ref mask, ref pattern, flowPtr, out var id),
                "PassThruStartMsgFilter");
            filterId = id;
        }
        finally
        {
            if (flowPtr != IntPtr.Zero)
            {
                Marshal.DestroyStructure<Msg>(flowPtr);
                Marshal.FreeHGlobal(flowPtr);
            }
        }
    }

    /// <summary>
    /// Standard J2534-2 SET_CONFIG(FD_CAN_DATA_PHASE_RATE), required by
    /// adapters that perform CAN FD bit-rate switching. The driver decides
    /// which rates its actual transceiver supports. Call BEFORE filters.
    /// </summary>
    internal void SetCanFdDataPhaseRate(uint bitRate)
    {
        EnsureConnected();
        if (protocolId is not (FdCan or FdIso15765) || bitRate == 0)
            throw new ArgumentException(
                "CAN FD channel and positive data bitrate required.");
        SetStandardConfig(FdCanDataPhaseRate, bitRate);
    }

    /// <summary>
    /// SAE J2534-2 SW_CAN_HS_DATA_RATE (0x8010). This asks the
    /// vendor driver to configure the optional hardware high-speed mode;
    /// it does not claim a transceiver or ECU supports mode switching.
    /// </summary>
    internal void SetSwCanHighSpeedRate(uint bitRate)
    {
        EnsureConnected();
        if (protocolId != SwCan || bitRate == 0)
            throw new ArgumentException(
                "An actual J2534-2 SW_CAN driver channel and a positive high-speed bitrate are required.");
        SetStandardConfig(SwCanHighSpeedDataRate, bitRate);
    }

    private void SetStandardConfig(uint parameter, uint value)
    {
        var config = Marshal.AllocHGlobal(Marshal.SizeOf<SConfig>());
        var list = Marshal.AllocHGlobal(Marshal.SizeOf<SConfigList>());
        try
        {
            Marshal.StructureToPtr(new SConfig {
                Parameter = parameter, Value = value
            }, config, false);
            Marshal.StructureToPtr(new SConfigList {
                NumOfParams = 1, ConfigPtr = config
            }, list, false);
            Require(ioctl!(channelId, SetConfigIoctl, list, IntPtr.Zero),
                $"PassThruIoctl(SET_CONFIG 0x{parameter:X4})");
        }
        finally
        {
            Marshal.FreeHGlobal(list);
            Marshal.FreeHGlobal(config);
        }
    }

    /// <summary>ISO 9141/14230 FIVE_BAUD_INIT via vendor J2534 IOCTL.</summary>
    internal byte[] FiveBaudInitialize(byte targetAddress)
    {
        EnsureKLine();
        var address = Marshal.AllocHGlobal(1);
        var result = Marshal.AllocHGlobal(16);
        var input = IntPtr.Zero;
        var output = IntPtr.Zero;
        try
        {
            Marshal.WriteByte(address, targetAddress);
            input = Marshal.AllocHGlobal(Marshal.SizeOf<SByteArray>());
            output = Marshal.AllocHGlobal(Marshal.SizeOf<SByteArray>());
            Marshal.StructureToPtr(new SByteArray { NumOfBytes = 1,
                BytePtr = address }, input, false);
            Marshal.StructureToPtr(new SByteArray { NumOfBytes = 16,
                BytePtr = result }, output, false);
            Require(ioctl!(channelId, FiveBaudInitIoctl, input, output),
                "PassThruIoctl(FIVE_BAUD_INIT)");
            var response = Marshal.PtrToStructure<SByteArray>(output);
            if (response.NumOfBytes > 16 || response.BytePtr != result)
                throw new InvalidDataException("K-Line init returned invalid keyword response.");
            var bytes = new byte[response.NumOfBytes];
            Marshal.Copy(result, bytes, 0, bytes.Length);
            return bytes;
        }
        finally
        {
            if (input != IntPtr.Zero) Marshal.FreeHGlobal(input);
            if (output != IntPtr.Zero) Marshal.FreeHGlobal(output);
            Marshal.FreeHGlobal(address);
            Marshal.FreeHGlobal(result);
        }
    }

    /// <summary>ISO 14230 FAST_INIT using real PassThruIoctl and vendor timing.</summary>
    internal byte[] FastInitialize(byte[] startCommunicationFrame)
    {
        EnsureKLine();
        if (startCommunicationFrame is null ||
            startCommunicationFrame.Length is < 1 or > 255)
            throw new ArgumentException("KWP2000 StartCommunication frame is required.");
        var size = Marshal.SizeOf<Msg>();
        var input = Marshal.AllocHGlobal(size);
        var output = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(MakeMsg(startCommunicationFrame, 0),
                input, false);
            Marshal.StructureToPtr(MakeMsg([], 0), output, false);
            Require(ioctl!(channelId, FastInitIoctl, input, output),
                "PassThruIoctl(FAST_INIT)");
            var response = Marshal.PtrToStructure<Msg>(output);
            if (response.DataSize > MaxData)
                throw new InvalidDataException("K-Line fast init exceeded J2534 message size.");
            return response.Data.AsSpan(0, (int)response.DataSize).ToArray();
        }
        finally
        {
            Marshal.DestroyStructure<Msg>(input);
            Marshal.DestroyStructure<Msg>(output);
            Marshal.FreeHGlobal(input);
            Marshal.FreeHGlobal(output);
        }
    }

    private void EnsureKLine()
    {
        EnsureConnected();
        if (protocolId is not (Iso9141 or Iso14230))
            throw new InvalidOperationException(
                "K-Line standard initialization is applicable to ISO 9141/14230 only.");
    }

    private Msg MakeMsg(byte[] data, uint flags)
    {
        var buffer = new byte[MaxData];
        Array.Copy(data, buffer, data.Length);
        return new Msg { ProtocolId = protocolId, TxFlags = flags,
            Data = buffer, DataSize = (uint)data.Length };
    }

    private T Get<T>(string symbol) where T : Delegate =>
        Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(library, symbol));

    private void EnsureConnected()
    {
        if (!channelOpened) throw new InvalidOperationException("No active vendor J2534 channel.");
    }

    private void Require(uint code, string operation)
    {
        if (code == 0) return;
        var text = new StringBuilder(256);
        try { lastError?.Invoke(text); } catch { }
        throw new IOException($"{operation}: J2534 status 0x{code:X8}; {text}");
    }

    public void Dispose()
    {
        if (channelOpened)
        {
            if (filterId.HasValue)
                try { stopFilter?.Invoke(channelId, filterId.Value); } catch { }
            try { disconnect?.Invoke(channelId); } catch { }
        }
        if (deviceOpened)
            try { close?.Invoke(deviceId); } catch { }
        filterId = null;
        channelOpened = deviceOpened = false;
        if (library != IntPtr.Zero)
        {
            NativeLibrary.Free(library);
            library = IntPtr.Zero;
        }
    }
}
