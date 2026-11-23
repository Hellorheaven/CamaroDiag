using System.Buffers.Binary;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1939-81 address-claim announcement decoder. This is passive
/// identification only; it does not arbitrate a claimed address, choose
/// a source address, or transmit any address-claim message on the bus.
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
