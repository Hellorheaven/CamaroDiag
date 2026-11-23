using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979-2 / ISO 14229 OBDonUDS single-DID vehicle identification.
/// F802 is the emissions VIN. This is distinct from classic J1979 Mode 09
/// (49 02 ...), and does not imply any ECU programming permission.
/// </summary>
internal static class ObdOnUdsVehicleIdentification
{
    internal const ushort VinDid = 0xF802;

    internal static byte[] ReadVin() =>
        ObdOnUdsServices.ReadObdDid(VinDid);

    internal static string DecodeVin(ReadOnlySpan<byte> response)
    {
        var raw = ObdOnUdsServices.DecodeReadDid(VinDid, response);
        if (raw.Length != 17)
            throw new InvalidDataException("OBDonUDS F802 VIN must have 17 bytes.");
        foreach (var value in raw)
        {
            if (!((value >= (byte)'A' && value <= (byte)'Z' &&
                value != (byte)'I' && value != (byte)'O' &&
                value != (byte)'Q') ||
                (value >= (byte)'0' && value <= (byte)'9')))
                throw new InvalidDataException(
                    "OBDonUDS VIN has invalid ASCII characters.");
        }
        return Encoding.ASCII.GetString(raw);
    }
}
