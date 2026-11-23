using System.Text;

namespace CamaroDiag.J2534Bridge;

/// <summary>
/// SAE J1979 Mode 09 PID 02 VIN, from an application payload that has already
/// been reassembled by ISO-TP. Does not parse raw CAN segments or J1979-2 DID
/// responses; non-CAN Mode 09 multi-message layouts are not inferred.
/// </summary>
internal static class ObdJ1979Vin
{
    internal static readonly byte[] Request = [0x09, 0x02];

    internal static string DecodeIsoTp(ReadOnlySpan<byte> response)
    {
        // Mode + 0x40, PID, message index (01), 17 ASCII VIN bytes.
        if (response.Length != 20 || response[0] != 0x49 ||
            response[1] != 0x02 || response[2] != 0x01)
            throw new InvalidDataException(
                "Expected one reassembled SAE J1979 Mode 09 PID 02 VIN record.");
        var vin = response[3..];
        foreach (var value in vin)
        {
            if (!((value >= 'A' && value <= 'Z' &&
                    value is not ('I' or 'O' or 'Q')) ||
                  (value >= '0' && value <= '9')))
                throw new InvalidDataException("Invalid ASCII VIN character.");
        }
        return Encoding.ASCII.GetString(vin);
    }
}
