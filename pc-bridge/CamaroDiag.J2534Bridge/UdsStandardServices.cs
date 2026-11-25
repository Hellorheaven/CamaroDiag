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

    /// <summary>
    /// ISO 14229 ReadDataByIdentifier supports several DIDs per request.
    /// Response items contain no general-purpose length field, so use
    /// DecodeDids with known per-DID payload lengths to correlate them.
    /// </summary>
    internal static byte[] ReadDids(IReadOnlyList<ushort> identifiers)
    {
        ArgumentNullException.ThrowIfNull(identifiers);
        if (identifiers.Count is < 1 or > 2047 ||
            identifiers.Distinct().Count() != identifiers.Count)
            throw new ArgumentException(
                "UDS multi-DID request needs 1–2047 distinct identifiers.");
        var request = new byte[1 + identifiers.Count * 2];
        request[0] = 0x22;
        for (var i = 0; i < identifiers.Count; i++)
            BinaryPrimitives.WriteUInt16BigEndian(
                request.AsSpan(1 + i * 2, 2), identifiers[i]);
        return request;
    }

    /// <summary>
    /// Strict ISO 14229 0x62 multi-DID response parsing with caller-supplied
    /// lengths. Rejects reordered, truncated and additional DID records.
    /// Lengths are data bytes after the two-byte DID, not total record sizes.
    /// </summary>
    internal static IReadOnlyList<(ushort Did, byte[] Data)> DecodeDids(
        ReadOnlySpan<byte> request, ReadOnlySpan<byte> response,
        IReadOnlyList<int> dataLengths)
    {
        ArgumentNullException.ThrowIfNull(dataLengths);
        if (request.Length < 3 || request[0] != 0x22 ||
            (request.Length - 1) % 2 != 0 ||
            dataLengths.Count != (request.Length - 1) / 2 ||
            dataLengths.Any(length => length < 0 || length > 4095))
            throw new ArgumentException(
                "UDS response decoding needs a valid multi-DID request and lengths.");
        if (response.Length < 3 || response[0] != 0x62)
            throw new InvalidDataException("Wrong UDS multi-DID positive response.");
        var offset = 1;
        var decoded = new List<(ushort Did, byte[] Data)>(dataLengths.Count);
        for (var i = 0; i < dataLengths.Count; i++)
        {
            var expectedDid = BinaryPrimitives.ReadUInt16BigEndian(
                request.Slice(1 + 2 * i, 2));
            var length = dataLengths[i];
            if (response.Length - offset < 2 + length ||
                BinaryPrimitives.ReadUInt16BigEndian(
                    response.Slice(offset, 2)) != expectedDid)
                throw new InvalidDataException(
                    "UDS multi-DID response is truncated, reordered or belongs to another request.");
            decoded.Add((expectedDid,
                response.Slice(offset + 2, length).ToArray()));
            offset += 2 + length;
        }
        if (offset != response.Length)
            throw new InvalidDataException(
                "UDS multi-DID response contains unexpected trailing records.");
        return decoded;
    }

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

    /// <summary>
    /// General ISO 14229 RequestDownload with explicit memoryAddress and
    /// memorySize widths. Each field may use 1–8 big-endian bytes. Address
    /// values and ranges must fit the selected widths. This only builds a
    /// PDU; the OEM must authorize the target mapping and memory operation.
    /// </summary>
    internal static byte[] RequestDownload(ulong address, ulong length,
        byte addressBytes, byte lengthBytes, byte dataFormatIdentifier = 0) =>
        UploadDownload(0x34, address, length,
            dataFormatIdentifier, addressBytes, lengthBytes);

    internal static byte[] RequestUpload(ulong address, ulong length,
        byte addressBytes, byte lengthBytes, byte dataFormatIdentifier = 0) =>
        UploadDownload(0x35, address, length,
            dataFormatIdentifier, addressBytes, lengthBytes);

    private static byte[] UploadDownload(byte service, uint address, uint length,
        byte dfi)
    {
        // Preserve the legacy 32-bit memory-space boundary even though the
        // generalized ALFI helper also supports explicit 64-bit addresses.
        if (length == 0 || (ulong)address + length > 0x1_0000_0000UL)
            throw new ArgumentOutOfRangeException(nameof(length),
                "32-bit ECU memory range wraps the selected address space.");
        return UploadDownload(service, (ulong)address, (ulong)length,
            dfi, addressBytes: 4, lengthBytes: 4);
    }

    private static byte[] UploadDownload(byte service, ulong address,
        ulong length, byte dfi, byte addressBytes, byte lengthBytes)
    {
        if (length == 0 ||
            addressBytes is < 1 or > 8 || lengthBytes is < 1 or > 8 ||
            (addressBytes < 8 && (address >> (addressBytes * 8)) != 0) ||
            (lengthBytes < 8 && (length >> (lengthBytes * 8)) != 0) ||
            // Include the last addressed byte, but never wrap ulong.
            length - 1 > ulong.MaxValue - address)
            throw new ArgumentOutOfRangeException(
                nameof(length), "UDS upload/download address or size exceeds selected ALFI width.");
        // An ALFI of 1..7 address bytes describes a smaller memory
        // address space than ulong. The complete requested range, not
        // merely its starting address, must fit that space.
        if (addressBytes < 8 &&
            length > (1UL << (addressBytes * 8)) - address)
            throw new ArgumentOutOfRangeException(
                nameof(length), "UDS memory range crosses the selected ALFI address boundary.");
        var pdu = new byte[3 + addressBytes + lengthBytes];
        pdu[0] = service;
        pdu[1] = dfi;
        pdu[2] = (byte)((lengthBytes << 4) | addressBytes);
        for (var i = 0; i < addressBytes; i++)
            pdu[3 + i] = (byte)(address >> (8 * (addressBytes - 1 - i)));
        for (var i = 0; i < lengthBytes; i++)
            pdu[3 + addressBytes + i] =
                (byte)(length >> (8 * (lengthBytes - 1 - i)));
        return pdu;
    }

    internal sealed record NegotiatedTransferSize(
        byte PositiveService, byte LengthFieldBytes,
        ulong MaxNumberOfBlockLength)
    {
        /// <summary>0x36 SID + blockSequenceCounter occupy two bytes.</summary>
        internal ulong MaxDataBytes =>
            MaxNumberOfBlockLength >= 2 ? MaxNumberOfBlockLength - 2 : 0;
    }

    /// <summary>
    /// ISO 14229 RequestDownload (0x74) and RequestUpload (0x75)
    /// positive responses expose maxNumberOfBlockLength. The high nibble
    /// of LFI specifies its big-endian byte width; the low nibble is
    /// reserved. Block size includes TransferData SID and block counter.
    /// Parsing does not authorize or start any ECU memory operation.
    /// </summary>
    internal static NegotiatedTransferSize DecodeTransferParameters(
        byte requestService, ReadOnlySpan<byte> response)
    {
        if (requestService is not (0x34 or 0x35))
            throw new ArgumentOutOfRangeException(nameof(requestService));
        var expectedSid = (byte)(requestService + 0x40);
        if (response.Length < 3 || response[0] != expectedSid)
            throw new InvalidDataException(
                "UDS transfer response must match RequestDownload or RequestUpload.");
        var lengthFormatIdentifier = response[1];
        var width = lengthFormatIdentifier >> 4;
        if (width is < 1 or > 8 ||
            (lengthFormatIdentifier & 0x0F) != 0 ||
            response.Length != 2 + width)
            throw new InvalidDataException(
                "UDS transfer maxNumberOfBlockLength has invalid LFI or byte length.");
        ulong size = 0;
        for (var i = 0; i < width; i++)
            size = (size << 8) | response[2 + i];
        if (size < 3)
            throw new InvalidDataException(
                "UDS TransferData block maximum cannot hold SID, counter and data.");
        return new(expectedSid, (byte)width, size);
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
            0x22 or 0x24 or 0x2E or 0x2F => 2,
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
        if (request[0] is 0x34 or 0x35)
            _ = DecodeTransferParameters(request[0], response);
        if (request[0] == 0x19)
        {
            var subfunction = request[1] & 0x7F;
            if (subfunction is 0x03 or 0x14 && (request.Length != 2 || (response.Length - 2) % 4 != 0))
                throw new InvalidDataException("Malformed DTC index or fault counter report.");
            if (subfunction == 0x0A && (request.Length != 2 || response.Length < 3 || (response.Length - 3) % 4 != 0))
                throw new InvalidDataException("Malformed supported DTC inventory.");
            if (subfunction == 9 && (request.Length != 5 || response.Length is not (3 or 9) ||
                (response.Length == 9 && !response.Slice(5, 3).SequenceEqual(request.Slice(2, 3)))))
                throw new InvalidDataException("DTC severity response belongs to another fault.");
            // ISO 14229 WWH-OBD subfunctions echo the functional group
            // immediately after the subfunction. Ignoring it can attribute
            // a safety/legislated group DTC to an emissions request.
            if (request.Length >= 5 && request[1] is 0x04 or 0x06)
            {
                if (response.Length < 5 ||
                    !response.Slice(2, 3).SequenceEqual(request.Slice(2, 3)))
                    throw new InvalidDataException(
                        "UDS DTC snapshot/extended-data response has wrong 24-bit DTC.");
                // 0xFF denotes "all" for snapshots; 0xFE/"all
                // regulated" and 0xFF/"all" apply to extended data.
                // A specific record ID in an actual returned record must
                // match the selected record. An empty response header
                // without records is not itself a mismatched record.
                if (request.Length >= 6 && response.Length >= 7 &&
                    request[5] != 0xFF &&
                    (request[1] != 0x06 || request[5] != 0xFE) &&
                    response[6] != request[5])
                    throw new InvalidDataException(
                        "UDS DTC record response belongs to another snapshot/extended record.");
            }
            if (request.Length >= 3 && request[1] is 0x42 or 0x55 or 0x56)
            {
                if (response.Length < 3 || response[2] != request[2])
                    throw new InvalidDataException(
                        "WWH-OBD response functional group does not match the request.");
                if (request[1] == 0x56 &&
                    (request.Length < 4 || response.Length < 6 ||
                     response[5] != request[3]))
                    throw new InvalidDataException(
                        "WWH-OBD readiness group response is not correlated.");
            }
        }
        return response.ToArray();
    }
}
