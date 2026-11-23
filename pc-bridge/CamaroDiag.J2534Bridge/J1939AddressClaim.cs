using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-81 address-claim codec and pure NAME conflict comparison.
/// Frame construction does not transmit, select a usable bus address or
/// perform the 250 ms physical claim/listen procedure.
/// </summary>
internal static class J1939AddressClaim
{
    internal const int AddressClaimPgn = 0xEE00;
    internal const int CannotClaimAddress = 0xFE;

    internal sealed record Identity(
        byte SourceAddress, ulong Name, uint IdentityNumber,
        ushort ManufacturerCode, byte EcuInstance,
        byte FunctionInstance, byte Function, byte VehicleSystem,
        byte VehicleSystemInstance, byte IndustryGroup,
        bool ArbitraryAddressCapable);

    internal enum ClaimConflict
    {
        DifferentAddress,
        LocalNameWins,
        PeerNameWins,
        DuplicateName
    }

    /// <summary>
    /// Construct the standard 8-byte J1939-81 address-claim frame only.
    /// No frame is sent and this does not qualify the address for use.
    /// Source 0xFE is the standard Cannot Claim Address indication.
    /// </summary>
    internal static IsoTpCanFdSession.CanFrame Build(
        byte sourceAddress, ulong name, byte priority = 6)
    {
        if (sourceAddress == 0xFF || name == ulong.MaxValue ||
            priority > 7)
            throw new ArgumentException("Invalid claimed source, NAME or priority.");
        var bytes = new byte[8];
        BinaryPrimitives.WriteUInt64LittleEndian(bytes, name);
        // SAE J1939-81 reserves source 0xFE for "Cannot Claim Address".
        // The ordinary J1939 TP builder deliberately refuses that source;
        // only this special management frame is allowed to use it.
        var id = sourceAddress == CannotClaimAddress ?
            ((uint)priority << 26) | ((uint)AddressClaimPgn << 8) |
                (0xFFu << 8) | sourceAddress :
            J1939ClassicTpSession.ArbitrationId(
                AddressClaimPgn, priority, sourceAddress, 0xFF);
        return new(id, false, bytes);
    }

    /// <summary>
    /// Lower numeric NAME has priority in a collision on the same source
    /// address. Duplicate NAME is ambiguous and must never be treated as
    /// an automatic successful claim.
    /// </summary>
    internal static ClaimConflict Compare(Identity peer, byte localAddress,
        ulong localName)
    {
        ArgumentNullException.ThrowIfNull(peer);
        if (localAddress >= 0xFE || localName == ulong.MaxValue)
            throw new ArgumentException("Local J1939 identity is not claimable.");
        if (peer.SourceAddress != localAddress)
            return ClaimConflict.DifferentAddress;
        if (peer.Name == localName)
            return ClaimConflict.DuplicateName;
        return localName < peer.Name ?
            ClaimConflict.LocalNameWins : ClaimConflict.PeerNameWins;
    }

    internal static Identity Decode(uint arbitrationId, ReadOnlySpan<byte> data)
    {
        var frame = StandardVehicleProtocols.ParseJ1939Id(arbitrationId);
        if (frame.Pgn != AddressClaimPgn || frame.Destination != 0xFF ||
            data.Length != 8 || frame.Source == 0xFF)
            throw new InvalidDataException("Expected 8-byte broadcast J1939 Address Claimed message.");
        var name = BinaryPrimitives.ReadUInt64LittleEndian(data);
        if (name == ulong.MaxValue)
            throw new InvalidDataException("Unspecified J1939 node NAME is not a valid identity.");
        return new Identity((byte)frame.Source, name,
            (uint)(name & 0x1F_FFFFu),
            (ushort)((name >> 21) & 0x7FF),
            (byte)((name >> 32) & 0x07),
            (byte)((name >> 35) & 0x1F),
            (byte)((name >> 40) & 0xFF),
            (byte)((name >> 49) & 0x7F),
            (byte)((name >> 56) & 0x0F),
            (byte)((name >> 60) & 0x07),
            ((name >> 63) & 1) != 0);
    }
}
