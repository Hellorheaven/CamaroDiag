using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Standard ISO 14229-1 service constructors and response matching for
/// BOTH read and write. Neither transport nor this codec grants ECU security
/// access; caller supplies the OEM-defined data, authenticated session and
/// operator's fresh OK -> Oui/Non consent for a mutating transaction.
/// </summary>
internal static class UdsStandardServices
{
    internal static byte[] Session(byte sessionType) => [0x10, sessionType];
    internal static byte[] Reset(byte resetType) => [0x11, resetType];
    internal static byte[] TesterPresent(bool suppressPositive = false) =>
        [0x3E, suppressPositive ? (byte)0x80 : (byte)0x00];
    internal static byte[] ReadDid(ushort did) => [0x22, (byte)(did >> 8), (byte)did];

    /// <summary>ISO 14229 ReadScalingDataByIdentifier, SID 0x24.</summary>
    internal static byte[] ReadScalingDid(ushort did) =>
        [0x24, (byte)(did >> 8), (byte)did];

    /// <summary>
    /// InputOutputControlByIdentifier SID 0x2F. Only the DID and the
    /// caller-supplied standard/OEM controlOptionRecord are assembled here.
    /// A write-like I/O control requires the full operator approval process.
    /// </summary>
    internal static byte[] ControlIo(ushort did,
        ReadOnlySpan<byte> controlOptionRecord)
    {
        if (controlOptionRecord.Length is < 1 or > 4088)
            throw new ArgumentOutOfRangeException(nameof(controlOptionRecord));
        var frame = new byte[3 + controlOptionRecord.Length];
        frame[0] = 0x2F;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(1), did);
        controlOptionRecord.CopyTo(frame.AsSpan(3));
        return frame;
    }

    /// <summary>
    /// ISO 14229 CommunicationControl SID 0x28, subfunction and
    /// communicationType. Suppress-positive bit is preserved in the
    /// requested subfunction exactly as supplied.
    /// </summary>
    internal static byte[] CommunicationControl(byte subFunction,
        byte communicationType, ReadOnlySpan<byte> extraParameters = default)
    {
        if ((subFunction & 0x7F) > 3 ||
            extraParameters.Length > 4088)
            throw new ArgumentOutOfRangeException(nameof(subFunction));
        var bytes = new byte[3 + extraParameters.Length];
        bytes[0] = 0x28;
        bytes[1] = subFunction;
        bytes[2] = communicationType;
        extraParameters.CopyTo(bytes.AsSpan(3));
        return bytes;
    }

    /// <summary>
    /// ISO 14229 ControlDTCSetting SID 0x85. OEM-defined records are
    /// passed through unchanged, with no presumed authorization.
    /// </summary>
    internal static byte[] ControlDtcSetting(byte settingType,
        ReadOnlySpan<byte> settingRecord = default)
    {
        if ((settingType & 0x7F) is not (1 or 2) ||
            settingRecord.Length > 4090)
            throw new ArgumentOutOfRangeException(nameof(settingType));
        var frame = new byte[2 + settingRecord.Length];
        frame[0] = 0x85;
        frame[1] = settingType;
        settingRecord.CopyTo(frame.AsSpan(2));
        return frame;
    }

    /// <summary>
    /// ReadDataByPeriodicIdentifier SID 0x2A. The caller must supply a
    /// standardized transmission mode and at least one periodic DID byte.
    /// </summary>
    internal static byte[] ReadPeriodicData(byte transmissionMode,
        ReadOnlySpan<byte> periodicIdentifiers)
    {
        if (periodicIdentifiers.Length is < 1 or > 4089)
            throw new ArgumentOutOfRangeException(nameof(periodicIdentifiers));
        var frame = new byte[2 + periodicIdentifiers.Length];
        frame[0] = 0x2A;
        frame[1] = transmissionMode;
        periodicIdentifiers.CopyTo(frame.AsSpan(2));
        return frame;
    }

    internal static byte[] WriteDid(ushort did, ReadOnlySpan<byte> value)
    {
        if (value.Length is < 1 or > 4091 - 3)
            throw new ArgumentOutOfRangeException(nameof(value));
        var pdu = new byte[3 + value.Length];
        pdu[0] = 0x2E;
        BinaryPrimitives.WriteUInt16BigEndian(pdu.AsSpan(1), did);
        value.CopyTo(pdu.AsSpan(3));
        return pdu;
    }

    internal static byte[] ReadMemory(uint address, uint length, byte addressBytes = 4,
        byte lengthBytes = 4) => MemoryPdu(0x23, address, length, addressBytes, lengthBytes);

    internal static byte[] WriteMemory(uint address, ReadOnlySpan<byte> bytes,
        byte addressBytes = 4, byte lengthBytes = 4)
    {
        if (bytes.Length == 0 || bytes.Length > 4091 - 10)
            throw new ArgumentOutOfRangeException(nameof(bytes));
        var prefix = MemoryPdu(0x3D, address, (uint)bytes.Length, addressBytes, lengthBytes);
        var result = new byte[prefix.Length + bytes.Length];
        prefix.CopyTo(result, 0);
        bytes.CopyTo(result.AsSpan(prefix.Length));
        return result;
    }

    internal static byte[] RequestDownload(uint address, uint length,
        byte dataFormatIdentifier = 0x00) =>
        UploadDownload(0x34, address, length, dataFormatIdentifier);

    internal static byte[] RequestUpload(uint address, uint length,
        byte dataFormatIdentifier = 0x00) =>
        UploadDownload(0x35, address, length, dataFormatIdentifier);

    private static byte[] UploadDownload(byte service, uint address, uint length, byte dfi)
    {
        if (length == 0 || (ulong)address + length > 0x1_0000_0000ul)
            throw new ArgumentOutOfRangeException(nameof(length));
        // ALFI 0x44; ECU-specific address/size width can be handled in a
        // separate profile. DFI is not presumed to imply compression support.
        return [service, dfi, 0x44,
            (byte)(address >> 24), (byte)(address >> 16), (byte)(address >> 8), (byte)address,
            (byte)(length >> 24), (byte)(length >> 16), (byte)(length >> 8), (byte)length];
    }

    internal static byte[] TransferData(byte counter, ReadOnlySpan<byte> data)
    {
        if (data.Length > 4089) throw new ArgumentOutOfRangeException(nameof(data));
        var pdu = new byte[2 + data.Length];
        pdu[0] = 0x36; pdu[1] = counter;
        data.CopyTo(pdu.AsSpan(2));
        return pdu;
    }

    internal static byte[] TransferExit(ReadOnlySpan<byte> parameters)
    {
        if (parameters.Length > 4090)
            throw new ArgumentOutOfRangeException(nameof(parameters));
        var pdu = new byte[1 + parameters.Length];
        pdu[0] = 0x37;
        parameters.CopyTo(pdu.AsSpan(1));
        return pdu;
    }

    internal static byte[] Routine(byte controlType, ushort routineId, ReadOnlySpan<byte> data)
    {
        if (controlType is < 1 or > 3 || data.Length > 4087)
            throw new ArgumentOutOfRangeException(nameof(data));
        var pdu = new byte[4 + data.Length];
        pdu[0] = 0x31;
        pdu[1] = controlType;
        BinaryPrimitives.WriteUInt16BigEndian(pdu.AsSpan(2), routineId);
        data.CopyTo(pdu.AsSpan(4));
        return pdu;
    }

    internal static byte[] SecurityAccess(byte subFunction, ReadOnlySpan<byte> data)
    {
        if (subFunction == 0 || subFunction == 0x80 || data.Length > 4089)
            throw new ArgumentOutOfRangeException(nameof(subFunction));
        var pdu = new byte[data.Length + 2];
        pdu[0] = 0x27; pdu[1] = subFunction;
        data.CopyTo(pdu.AsSpan(2));
        return pdu;
    }

    internal static byte[] ClearDtc(uint group)
    {
        if (group > 0xFFFFFF) throw new ArgumentOutOfRangeException(nameof(group));
        return [0x14, (byte)(group >> 16), (byte)(group >> 8), (byte)group];
    }

    internal static byte[] ReadDtc(byte subFunction, ReadOnlySpan<byte> parameters)
    {
        if (parameters.Length > 4089) throw new ArgumentOutOfRangeException(nameof(parameters));
        var pdu = new byte[2 + parameters.Length];
        pdu[0] = 0x19; pdu[1] = subFunction;
        parameters.CopyTo(pdu.AsSpan(2));
        return pdu;
    }

    private static byte[] MemoryPdu(byte sid, uint address, uint length,
        byte addressBytes, byte sizeBytes)
    {
        if (length == 0 || addressBytes is < 1 or > 4 ||
            sizeBytes is < 1 or > 4 || (ulong)address + length > 0x1_0000_0000ul ||
            (addressBytes < 4 && ((ulong)address >> (addressBytes * 8)) != 0) ||
            (sizeBytes < 4 && ((ulong)length >> (sizeBytes * 8)) != 0))
            throw new ArgumentOutOfRangeException(nameof(length));
        var request = new byte[2 + addressBytes + sizeBytes];
        request[0] = sid;
        request[1] = (byte)((sizeBytes << 4) | addressBytes);
        for (int i = 0; i < addressBytes; i++)
            request[2 + i] = (byte)(address >> ((addressBytes - 1 - i) * 8));
        for (int i = 0; i < sizeBytes; i++)
            request[2 + addressBytes + i] = (byte)(length >> ((sizeBytes - 1 - i) * 8));
        return request;
    }

    /// <summary>Validate response to a specific in-flight UDS request.</summary>
    internal static byte[] RequireResponse(ReadOnlySpan<byte> request, ReadOnlySpan<byte> response)
    {
        if (request.IsEmpty || response.IsEmpty)
            throw new InvalidDataException("Incomplete UDS request/response.");
        if (response[0] == 0x7F)
        {
            if (response.Length == 3 && response[1] == request[0])
                throw new IOException($"UDS negative response 0x{response[2]:X2}.");
            throw new InvalidDataException("UDS negative response does not correspond to request.");
        }
        if (response[0] != unchecked((byte)(request[0] + 0x40)))
            throw new InvalidDataException("Wrong UDS response service.");
        // Service-specific echoed identifiers must match the *actual*
        // request. This does not assert that the ECU memory was programmed.
        var echoed = request[0] switch
        {
            0x22 or 0x2E or 0x2F => 2,
            0x31 => 3,
            0x10 or 0x11 or 0x27 or 0x36 or 0x19 or
                0x28 or 0x3E or 0x85 => 1,
            _ => 0
        };
        if (echoed != 0 && (request.Length < echoed + 1 ||
            response.Length < echoed + 1))
            throw new InvalidDataException("Missing UDS echoed identifier.");
        for (int i = 1; i <= echoed; i++)
        {
            var expected = (request[0] is 0x10 or 0x11 or 0x31 or 0x27 or
                    0x19 or 0x28 or 0x3E or 0x85)
                && i == 1 ? (byte)(request[i] & 0x7F) : request[i];
            if (response[i] != expected)
                throw new InvalidDataException("Stale/misdirected UDS reply identifier.");
        }
        return response.ToArray();
    }
}
