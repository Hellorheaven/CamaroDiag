using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// Normative protocol map, not a hardware support claim. J2534 IDs below are
/// SAE J2534-1 04.04/05.00 IDs, not CamaroDiag inventions. CAN FD extensions
/// must be negotiated with the particular J2534-2/vendor implementation.
/// </summary>
internal static class StandardVehicleProtocols
{
    internal sealed record Entry(string Name, string Standard, string Layer,
        uint? J2534ProtocolId, bool TransportPresent, bool PhysicalWriteQualified,
        string Scope = "Adapter-dependent; not physically qualified");

    internal static readonly IReadOnlyList<Entry> Catalog =
    [
        new("J1850 VPW", "SAE J1850", "physical/data link", 0x01, false, false),
        new("J1850 PWM", "SAE J1850", "physical/data link", 0x02, false, false),
        new("K-Line ISO 9141-2", "ISO 9141-2", "physical/data link", 0x03, false, false),
        new("K-Line KWP2000", "ISO 14230-1/2/3/4", "physical/transport/application", 0x04, false, false),
        new("CAN 11/29-bit", "ISO 11898 / SAE J2284", "physical/data link", 0x05, false, false),
        new("ISO-TP / UDS over CAN", "ISO 15765-2 / ISO 14229", "transport/application", 0x06, true, false),
        new("GM single-wire CAN", "SAE J2411 / GMLAN", "physical/transport", 0x8007, true, false),
        new("CAN FD + ISO-TP", "ISO 11898-1 / ISO 15765-2:2024", "physical/transport", null, true, false,
            "Raw FD_CAN ISO-TP SF/FF/CF/FC and mixed/extended addressing; driver and ECU unverified"),
        new("DoIP", "ISO 13400-2 / ISO 14229-5", "network/transport", null, true, false),
        new("OBD-II", "SAE J1979 / ISO 15031-5", "application", null, true, false),
        new("OBDonUDS", "SAE J1979-2", "application", null, true, false,
            "F810 identification, emissions DIDs and UDS DTC 0x19/0x02 only; full service mapping pending"),
        new("J1939 classical CAN", "SAE J1939-21 / J1939-73", "network/application", null, true, false,
            "J1939-21 BAM/RTS/CTS, Request, DM1/DM2, initial NAME claim; hardware unverified"),
        new("J1939 CAN FD", "SAE J1939-22", "network/transport", null, true, false,
            "FEFF Multi-PG and FD.TP BAM/CMDT TOS2 no assurance; FBFF/security/concurrency pending"),
    ];

    internal static Entry Find(string name) => Catalog.Single(p => p.Name == name);

    /// <summary>Frame payload capacity by standardized CAN FD DLC (not ISO-TP PDU length).</summary>
    internal static int CanFdDlcToLength(int dlc) => dlc switch
    {
        >= 0 and <= 8 => dlc,
        9 => 12, 10 => 16, 11 => 20, 12 => 24,
        13 => 32, 14 => 48, 15 => 64,
        _ => throw new ArgumentOutOfRangeException(nameof(dlc))
    };

    internal static int CanFdLengthToDlc(int length) => length switch
    {
        >= 0 and <= 8 => length,
        12 => 9, 16 => 10, 20 => 11, 24 => 12,
        32 => 13, 48 => 14, 64 => 15,
        _ => throw new ArgumentOutOfRangeException(nameof(length),
            "CAN-FD payload length must match an ISO 11898 DLC value.")
    };

    /// <summary>SAE J1939 29-bit identifier; PDU1 PGN clears destination bits.</summary>
    internal static (int Priority, int Pgn, int Source, int? Destination) ParseJ1939Id(uint identifier)
    {
        if (identifier > 0x1FFFFFFF) throw new ArgumentOutOfRangeException(nameof(identifier));
        var priority = (int)((identifier >> 26) & 7);
        var page = (int)((identifier >> 24) & 3);
        var pf = (int)((identifier >> 16) & 0xFF);
        var ps = (int)((identifier >> 8) & 0xFF);
        var source = (int)(identifier & 0xFF);
        var pgn = (page << 16) | (pf << 8) | (pf < 240 ? 0 : ps);
        return (priority, pgn, source, pf < 240 ? ps : null);
    }

    /// <summary>
    /// Decode an ISO 14230 physically addressed full K-Line frame. This
    /// expects the original wire header and checksum to be present; some
    /// J2534 vendor DLLs strip these bytes before returning messages.
    /// </summary>
    internal static byte[] ParseKwpPhysicalFrame(ReadOnlySpan<byte> frame,
        byte expectedTarget, byte expectedSource)
    {
        if (frame.Length < 5 || (frame[0] & 0xC0) != 0x80)
            throw new InvalidDataException("Unsupported KWP physical-frame header.");
        var length = frame[0] & 0x3F;
        var extendedLength = length == 0;
        var headerLength = extendedLength ? 4 : 3;
        if (extendedLength && frame.Length >= 4)
            length = frame[3];
        if (length == 0 || frame.Length != headerLength + length + 1)
            throw new InvalidDataException("KWP physical-frame length mismatch.");
        if (frame[1] != expectedTarget || frame[2] != expectedSource)
            throw new InvalidDataException("KWP physical-frame logical addresses mismatch.");
        var checksum = 0;
        for (var i = 0; i < frame.Length - 1; i++)
            checksum = (checksum + frame[i]) & 0xFF;
        if (checksum != frame[^1])
            throw new InvalidDataException("KWP physical-frame checksum mismatch.");
        return frame.Slice(headerLength, length).ToArray();
    }

    /// <summary>
    /// ISO 14230 physical-addressing long header with additive checksum.
    /// This only frames KWP messages: it does NOT perform K-Line initialization.
    /// </summary>
    internal static byte[] BuildKwpPhysicalFrame(byte target, byte source, byte[] payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        if (target == source || payload.Length is < 1 or > 255)
            throw new ArgumentException("Invalid KWP physical addresses or payload size.");
        var shortHeader = payload.Length <= 63;
        var headerLength = shortHeader ? 3 : 4;
        var frame = new byte[headerLength + payload.Length + 1];
        frame[0] = shortHeader ? (byte)(0x80 | payload.Length) : (byte)0x80;
        frame[1] = target;
        frame[2] = source;
        if (!shortHeader) frame[3] = (byte)payload.Length;
        payload.CopyTo(frame, headerLength);
        frame[^1] = (byte)(frame.AsSpan(0, frame.Length - 1).ToArray()
            .Aggregate(0, (sum, b) => sum + b) & 0xFF);
        return frame;
    }
}

/// <summary>
/// ISO 13400-2 DoIP transport PDU. Framing does not authenticate a DoIP
/// endpoint and is never sufficient authority for an ECU write.
/// </summary>
internal static class DoIpCodec
{
    internal const byte Version = 0x02;
    internal const ushort GenericNegativeAck = 0x0000;
    internal const ushort VehicleIdentificationRequest = 0x0001;
    internal const ushort VehicleIdentificationRequestEid = 0x0002;
    internal const ushort VehicleIdentificationRequestVin = 0x0003;
    internal const ushort VehicleIdentificationResponse = 0x0004;
    internal const ushort RoutingActivationRequest = 0x0005;
    internal const ushort RoutingActivationResponse = 0x0006;
    internal const ushort AliveCheckRequest = 0x0007;
    internal const ushort AliveCheckResponse = 0x0008;
    internal const ushort EntityStatusRequest = 0x4001;
    internal const ushort EntityStatusResponse = 0x4002;
    internal const ushort PowerModeRequest = 0x4003;
    internal const ushort PowerModeResponse = 0x4004;
    internal const ushort DiagnosticMessage = 0x8001;
    internal const ushort DiagnosticPositiveAck = 0x8002;
    internal const ushort DiagnosticNegativeAck = 0x8003;
    // Explicit bounded implementation ceiling, not the ISO 13400 32-bit
    // wire limit. Large OEM transfers need streaming and gateway limits.
    internal const int MaxPayload = 1024 * 1024;

    internal sealed record Message(byte Version, ushort Type, byte[] Payload);

    internal static byte[] Encode(ushort type, ReadOnlySpan<byte> payload, byte version = Version)
    {
        if (version is not (0x02 or 0x03) || payload.Length > MaxPayload)
            throw new ArgumentOutOfRangeException(nameof(payload));
        var frame = new byte[8 + payload.Length];
        frame[0] = version;
        frame[1] = (byte)~version;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(2), type);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(4), (uint)payload.Length);
        payload.CopyTo(frame.AsSpan(8));
        return frame;
    }

    /// <summary>
    /// ISO 13400 generic header NACK is independent of diagnostic
    /// acknowledgement (0x8003). Do not report it as an unrelated UDS
    /// response or pretend the requested command succeeded.
    /// </summary>
    internal static void RequireNoGenericNack(Message message)
    {
        if (message.Type != GenericNegativeAck) return;
        if (message.Payload.Length != 1 || message.Payload[0] > 4)
            throw new InvalidDataException("Malformed DoIP generic header NACK.");
        var reason = message.Payload[0] switch
        {
            0 => "invalid header pattern",
            1 => "unsupported payload type",
            2 => "payload too large",
            3 => "out of buffer memory",
            4 => "invalid payload length",
            _ => "unknown"
        };
        throw new IOException(
            $"DoIP generic header NACK 0x{message.Payload[0]:X2} ({reason}).");
    }

    internal static int ReadPayloadLength(ReadOnlySpan<byte> header)
    {
        if (header.Length != 8 || header[0] is not (0x02 or 0x03) ||
            header[1] != (byte)~header[0])
            throw new InvalidDataException("DoIP protocol header version/inverse mismatch.");
        var size = BinaryPrimitives.ReadUInt32BigEndian(header.Slice(4, 4));
        if (size > MaxPayload)
            throw new InvalidDataException("DoIP payload exceeds bounded diagnostic transport.");
        return (int)size;
    }

    internal static Message Decode(byte[] frame)
    {
        ArgumentNullException.ThrowIfNull(frame);
        if (frame.Length < 8) throw new InvalidDataException("Incomplete DoIP header.");
        var length = ReadPayloadLength(frame.AsSpan(0, 8));
        if (frame.Length != 8 + length)
            throw new InvalidDataException("DoIP declared payload size is not the physical frame size.");
        return new Message(frame[0], BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(2, 2)),
            frame.AsSpan(8).ToArray());
    }

    internal sealed record EntityStatus(byte NodeType, byte MaxSockets,
        byte CurrentSockets, uint? MaxDataSize);

    internal static EntityStatus ParseEntityStatus(Message response)
    {
        if (response.Type != EntityStatusResponse ||
            response.Payload.Length is not (3 or 7) ||
            response.Payload[0] > 1)
            throw new InvalidDataException("Invalid DoIP entity status response.");
        return new EntityStatus(response.Payload[0], response.Payload[1],
            response.Payload[2], response.Payload.Length == 7 ?
                BinaryPrimitives.ReadUInt32BigEndian(response.Payload.AsSpan(3, 4)) :
                null);
    }

    internal static byte ParseDiagnosticPowerMode(Message response)
    {
        if (response.Type != PowerModeResponse ||
            response.Payload.Length != 1 || response.Payload[0] > 2)
            throw new InvalidDataException("Invalid DoIP diagnostic power mode response.");
        return response.Payload[0]; // 0=not ready, 1=ready, 2=not supported
    }

    internal static byte[] RoutingActivation(ushort testerAddress,
        byte activationType = 0x00, byte version = Version)
    {
        var payload = new byte[7];
        BinaryPrimitives.WriteUInt16BigEndian(payload, testerAddress);
        payload[2] = activationType;
        return Encode(RoutingActivationRequest, payload, version);
    }

    internal static void RequireRoutingActive(Message response, ushort testerAddress)
    {
        if (response.Type != RoutingActivationResponse ||
            response.Payload.Length is not (9 or 13) ||
            BinaryPrimitives.ReadUInt16BigEndian(response.Payload) != testerAddress ||
            response.Payload[4] != 0x10)
            throw new InvalidDataException("DoIP routing activation not positively acknowledged.");
    }

    internal static byte[] ReplyToAliveCheck(Message request, ushort testerAddress)
    {
        if (request.Type != AliveCheckRequest || request.Payload.Length != 0)
            throw new InvalidDataException("DoIP alive-check request requires empty payload.");
        var payload = new byte[2];
        BinaryPrimitives.WriteUInt16BigEndian(payload, testerAddress);
        return Encode(AliveCheckResponse, payload, request.Version);
    }

    internal static byte[] Diagnostic(ushort testerAddress,
        ushort ecuAddress, byte[] uds, byte version = Version)
    {
        ArgumentNullException.ThrowIfNull(uds);
        if (testerAddress == ecuAddress || uds.Length is < 1 or > MaxPayload - 4)
            throw new ArgumentException("Invalid DoIP logical addresses or UDS size.");
        var data = new byte[uds.Length + 4];
        BinaryPrimitives.WriteUInt16BigEndian(data.AsSpan(0), testerAddress);
        BinaryPrimitives.WriteUInt16BigEndian(data.AsSpan(2), ecuAddress);
        uds.CopyTo(data, 4);
        return Encode(DiagnosticMessage, data, version);
    }

    internal static byte[] RequireDiagnosticResponse(Message response,
        ushort testerAddress, ushort ecuAddress)
    {
        if (response.Type != DiagnosticMessage || response.Payload.Length < 5 ||
            BinaryPrimitives.ReadUInt16BigEndian(response.Payload) != ecuAddress ||
            BinaryPrimitives.ReadUInt16BigEndian(response.Payload.AsSpan(2)) != testerAddress)
            throw new InvalidDataException("DoIP diagnostic response addresses/type invalid.");
        return response.Payload.AsSpan(4).ToArray();
    }

    internal static bool IsCorrelatedDiagnosticAck(Message response,
        ushort testerAddress, ushort ecuAddress)
    {
        if (response.Type is not (DiagnosticPositiveAck or DiagnosticNegativeAck) ||
            response.Payload.Length < 5 ||
            BinaryPrimitives.ReadUInt16BigEndian(response.Payload) != ecuAddress ||
            BinaryPrimitives.ReadUInt16BigEndian(response.Payload.AsSpan(2)) != testerAddress)
            return false;
        if (response.Type == DiagnosticNegativeAck)
            throw new IOException($"DoIP gateway negative ACK code 0x{response.Payload[4]:X2}.");
        if (response.Payload[4] != 0x00)
            throw new InvalidDataException(
                "DoIP positive diagnostic acknowledgement must use code 0x00.");
        return true;
    }
}
